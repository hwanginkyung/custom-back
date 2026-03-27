package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.dto.EvidenceBox;
import exps.cariv.domain.clova.dto.FieldEvidence;
import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.TextLine;
import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.layout.NormalizedLayout;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 자동차등록증 OCR 파서 v5
 *
 * 흐름: OCR bbox → 좌표 정규화 → 줄 묶기 → anchor 탐색 → 오른쪽 값 추출 → normalize → 구조화 JSON
 *
 * 모든 거리 기준을 문서 크기 대비 비율로 처리하여
 * 해상도/확대축소/촬영조건에 무관하게 동작
 */
@Slf4j
public class VehicleRegistrationParser {

    private final List<OcrWord> allWords;
    private final List<TextLine> lines;

    // ── 문서 좌표 정규화 ──
    private final double docWidth;
    private final double docHeight;
    private final double lineYThresholdRatio;

    // 값 패턴
    private static final Pattern PLATE = Pattern.compile("\\d{2,3}[가-힣]\\d{4}");
    private static final Pattern VIN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern DATE_YMD = Pattern.compile("(?<!\\d)(\\d{4})\\s*(?:년|[./\\-])?\\s*(\\d{1,2})\\s*(?:월|[./\\-])?\\s*(\\d{1,2})\\s*(?:일)?(?!\\d)");
    private static final Pattern DATE_YM = Pattern.compile("(?<!\\d)(\\d{4})\\s*(?:년|[./\\-])?\\s*(\\d{1,2})\\s*(?:월)?(?!\\d)");
    private static final Pattern FIELD_NUM_PREFIX = Pattern.compile("^[\\d①-⑳㉑-㉟\\s]+");
    private static final Pattern OWNER_ID_WITH_HYPHEN = Pattern.compile("(\\d{6})\\s*[-:]\\s*([\\d*X]{7})");
    private static final Pattern OWNER_ID_13_DIGITS = Pattern.compile("(?<!\\d)(\\d{13})(?!\\d)");
    private static final Pattern OWNER_ID_13_MASKED = Pattern.compile("(?<![\\d*X])(\\d{6})([\\d*X]{7})(?![\\d*X])");
    private static final Pattern OWNER_ID_CANONICAL = Pattern.compile("^(\\d{6})-([\\d*]{7})$");
    private static final String[] MODEL_DATE_ANCHORS = {
            "형식및제작연월", "형식및모델연도", "형식밋제작연월",
            "형식밀제작연월", "형식및제작연도", "형식및연식",
            "형식및연식모델연도", "형식및연식모델년도"
    };

    private final Map<String, FieldEvidence> evidenceMap = new LinkedHashMap<>();

    // 필드 라벨로 인식할 키워드 셋
    private static final Set<String> KNOWN_LABELS = Set.of(
            "자동차등록번호", "차종", "용도", "차명", "형식", "차대번호", "원동기형식",
            "사용본거지", "성명", "생년월일", "법인등록번호", "최초등록일",
            "길이", "너비", "높이", "총중량", "승차정원", "최대적재량",
            "배기량", "정격출력", "기통수", "연료", "제원관리번호",
            "형식및제작연월", "형식및모델연도", "형식밋제작연월",
            "형식및제작연도", "형식및연식", "형식및연식모델연도",
            "형식승인번호", "구동축전지", "검사유효기간",
            "등록번호판", "저당권등록사실"
    );

    /** 제원 영역의 시작 Y좌표 (절대좌표) */
    private double specSectionY = Double.MAX_VALUE;

    private final QualityGate qualityGate;
    private final AnchorResolver anchorResolver;

    private static class QualityGate {
        private final boolean passed;
        private final double score;
        private final String reason;

        private QualityGate(boolean passed, double score, String reason) {
            this.passed = passed;
            this.score = score;
            this.reason = reason;
        }
    }

    private static class CandidateValue {
        private final String value;
        private final OcrWord valueWord;
        private final double score;
        private final String raw;

        private CandidateValue(String value, OcrWord valueWord, double score, String raw) {
            this.value = value;
            this.valueWord = valueWord;
            this.score = score;
            this.raw = raw;
        }
    }

    private static class Extracted {
        private final String value;
        private final OcrWord anchorWord;
        private final OcrWord valueWord;
        private final double confidence;
        private final List<String> candidates;

        private Extracted(String value, OcrWord anchorWord, OcrWord valueWord, double confidence, List<String> candidates) {
            this.value = value;
            this.anchorWord = anchorWord;
            this.valueWord = valueWord;
            this.confidence = confidence;
            this.candidates = candidates;
        }

        private static Extracted empty() {
            return new Extracted(null, null, null, 0.0, Collections.emptyList());
        }
    }

    private enum KgFieldType {
        WEIGHT,
        MAX_LOAD
    }

    private static class ParsedKg {
        private final int value;
        private final boolean hasKgUnit;

        private ParsedKg(int value, boolean hasKgUnit) {
            this.value = value;
            this.hasKgUnit = hasKgUnit;
        }
    }

    public VehicleRegistrationParser(List<OcrWord> words) {
        this(words, 0.5);
    }

    public VehicleRegistrationParser(List<OcrWord> words, double lineYThresholdRatio) {
        this.allWords = words;
        this.lineYThresholdRatio = sanitizeLineYThresholdRatio(lineYThresholdRatio);
        this.lines = LineGrouper.group(words, this.lineYThresholdRatio);

        // ── 문서 경계 계산 (모든 bbox에서 최대 좌표 추출) ──
        double maxX = 0, maxY = 0;
        for (OcrWord w : words) {
            maxX = Math.max(maxX, w.rightX());
            maxY = Math.max(maxY, w.bottomY());
        }
        this.docWidth = Math.max(maxX, 1);   // 0 방지
        this.docHeight = Math.max(maxY, 1);

        log.debug("Document bounds: {}x{}, lineYThresholdRatio={}", docWidth, docHeight, this.lineYThresholdRatio);
        this.anchorResolver = new AnchorResolver(allWords, lines);
        detectSpecSectionY();
        this.qualityGate = evaluateQualityGate();
    }

    public VehicleRegistrationParser(NormalizedLayout layout) {
        this(layout, 0.5);
    }

    public VehicleRegistrationParser(NormalizedLayout layout, double lineYThresholdRatio) {
        this(layout.toOcrWords(), lineYThresholdRatio);
    }

    private double sanitizeLineYThresholdRatio(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return 0.5;
        if (ratio < 0.2) return 0.2;
        if (ratio > 1.5) return 1.5;
        return ratio;
    }

    // ══════════════════════════════════════════════════
    //  좌표 정규화 헬퍼
    //  비율(0.0~1.0) → 현재 문서의 절대 픽셀로 변환
    // ══════════════════════════════════════════════════

    /** X 비율 → 절대 픽셀 */
    private int rx(double ratio) {
        return (int) (docWidth * ratio);
    }

    /** Y 비율 → 절대 픽셀 */
    private int ry(double ratio) {
        return (int) (docHeight * ratio);
    }

    /** 제원 영역 시작 Y에서 약간 위 (여유분) */
    private double specMinY() {
        if (specSectionY == Double.MAX_VALUE) return Double.MAX_VALUE;
        return specSectionY - ry(0.02);
    }

    /** "제원" 또는 "제 원" 텍스트의 Y좌표 감지 */
    private void detectSpecSectionY() {
        for (OcrWord w : allWords) {
            String t = w.getText().replace(" ", "");
            if (t.contains("제원") && !t.contains("제원관리")) {
                specSectionY = w.getY();
                return;
            }
        }
        for (TextLine line : lines) {
            String compact = line.fullText().replace(" ", "");
            if (compact.contains("제원") && !compact.contains("제원관리")) {
                specSectionY = line.avgY();
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  파싱 메인
    // ══════════════════════════════════════════════════

    public VehicleRegistration parse() {
        VehicleRegistration reg = new VehicleRegistration();
        reg.setQualityGatePassed(qualityGate.passed);
        reg.setQualityScore(round4(qualityGate.score));
        reg.setNeedsRetry(!qualityGate.passed);
        reg.setQualityReason(qualityGate.reason);

        if (!qualityGate.passed) {
            reg.setEvidence(evidenceMap);
            return reg;
        }

        // ── 기본 정보 ──
        Extracted plate = extractPlateNumberScored();
        reg.setVehicleNo(plate.value);
        putEvidence("vehicleNo", plate);

        reg.setModelName(rightOfBounded(0.30, "차명", "차 명"));
        putSimpleEvidence("modelName", reg.getModelName(), findAnchorWord("차명"));

        Extracted vin = extractVinScored();
        reg.setVin(vin.value);
        putEvidence("vin", vin);

        reg.setEngineType(extractEngineType());
        putSimpleEvidence("engineType", reg.getEngineType(), findAnchorWord("원동기형식"));

        extractModelAndDate(reg);
        OcrWord modelAnchor = findModelDateAnchorWord();
        putSimpleEvidence("modelCode", reg.getModelCode(), modelAnchor);
        putSimpleEvidence("manufactureYearMonth", reg.getManufactureYearMonth(), modelAnchor);
        putSimpleEvidence("modelYear", reg.getModelYear() == null ? null : String.valueOf(reg.getModelYear()), modelAnchor);

        Extracted firstDate = extractDateScored(reg.getModelYear(), "최초등록일", "최초등록");
        reg.setFirstRegistratedAt(firstDate.value);
        putEvidence("firstRegistratedAt", firstDate);

        reg.setCarType(extractVehicleType());
        putSimpleEvidence("carType", reg.getCarType(), findAnchorWord("차종"));

        reg.setVehicleUse(extractUsage());
        putSimpleEvidence("vehicleUse", reg.getVehicleUse(), findAnchorWord("용도"));

        reg.setFuelType(extractFuelType());
        putSimpleEvidence("fuelType", reg.getFuelType(), findAnchorWord("연료"));

        // ── 소유자 ──
        reg.setOwnerName(extractOwnerName());
        putSimpleEvidence("ownerName", reg.getOwnerName(), findAnchorWord("성명"));

        Extracted ownerId = extractOwnerIdScored();
        reg.setOwnerId(ownerId.value);
        putEvidence("ownerId", ownerId);

        reg.setAddress(cleanAddress(extractAddress(), reg.getEngineType()));
        putSimpleEvidence("address", reg.getAddress(), findAnchorWord("사용본거지"));

        // ── 제원 ──
        reg.setLengthVal(extractDimension("길이", "길 이"));
        putSimpleEvidence("lengthVal", reg.getLengthVal(), findAnchorWord("길이", specMinY()));

        reg.setWidthVal(extractDimension("너비", "너 비"));
        putSimpleEvidence("widthVal", reg.getWidthVal(), findAnchorWord("너비", specMinY()));

        reg.setHeightVal(extractDimension("높이", "높 이"));
        putSimpleEvidence("heightVal", reg.getHeightVal(), findAnchorWord("높이", specMinY()));

        Extracted weight = extractKgScored(KgFieldType.WEIGHT, "총중량", "총 중 량");
        reg.setWeight(weight.value);
        putEvidence("weight", weight);

        reg.setSeating(extractSeating());
        putSimpleEvidence("seating", reg.getSeating(), findAnchorWord("승차정원", specMinY()));

        Integer weightKg = parseKgInt(reg.getWeight());
        Extracted maxLoad = extractKgScored(KgFieldType.MAX_LOAD, weightKg, "최대적재량", "최대 적재량", "적재량");
        reg.setMaxLoad(maxLoad.value);
        putEvidence("maxLoad", maxLoad);

        Extracted displacement = extractDisplacementScored();
        reg.setDisplacement(parseNullableInt(displacement.value));
        putEvidence("displacement", displacement);

        reg.setPower(extractPower());
        putSimpleEvidence("power", reg.getPower(), findAnchorWord("정격출력", specMinY()));

        applyPostValidation(reg);
        reg.setEvidence(evidenceMap);

        return reg;
    }

    // ══════════════════════════════════════════════════
    //  핵심: anchor → 오른쪽 값 추출 (비율 기반)
    // ══════════════════════════════════════════════════

    /**
     * anchor 오른쪽 값 추출 (문서 폭 대비 xRatio 범위 이내)
     */
    private String rightOfBounded(double xRatio, String... anchorCandidates) {
        int maxXRange = rx(xRatio);
        for (String anchor : anchorCandidates) {
            OcrWord anchorWord = findAnchorWord(anchor);
            if (anchorWord == null) continue;

            TextLine line = findLineOf(anchorWord);
            if (line == null) continue;

            List<OcrWord> rightWords = getRightWords(line, anchorWord, maxXRange);
            List<OcrWord> valueWords = trimAtNextAnchor(rightWords);
            if (!valueWords.isEmpty()) {
                return joinWords(valueWords);
            }

            // 같은 줄에 값이 없으면 → 아래 줄 우선 탐색
            int lineIdx = lines.indexOf(line);
            for (int delta : new int[]{1, -1}) {
                int adj = lineIdx + delta;
                if (adj < 0 || adj >= lines.size()) continue;
                TextLine adjLine = lines.get(adj);
                List<OcrWord> adjRight = getRightWords(adjLine, anchorWord, maxXRange);
                List<OcrWord> adjValues = trimAtNextAnchor(adjRight);
                if (!adjValues.isEmpty()) {
                    return joinWords(adjValues);
                }
            }
        }
        return null;
    }

    /**
     * anchor 오른쪽의 단어들 (절대 픽셀 범위 - 이미 비율 변환된 값)
     */
    private List<OcrWord> getRightWords(TextLine line, OcrWord anchor, int maxXRange) {
        double minX = anchor.rightX() - rx(0.005);
        double maxX = anchor.rightX() + maxXRange;
        return line.getWords().stream()
                .filter(w -> w.getX() > minX && w.getX() < maxX && w != anchor)
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());
    }

    /** maxXRange 제한 없는 버전 */
    private List<OcrWord> getRightWordsUnlimited(TextLine line, OcrWord anchor) {
        double minX = anchor.rightX() - rx(0.005);
        return line.getWords().stream()
                .filter(w -> w.getX() > minX && w != anchor)
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());
    }

    /**
     * 다음 필드 라벨(anchor)이 나오면 거기서 자름
     */
    private List<OcrWord> trimAtNextAnchor(List<OcrWord> words) {
        List<OcrWord> result = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            OcrWord w = words.get(i);
            if (isFieldLabel(w.getText())) {
                break;
            }
            if (isDigitsOnly(w.getText()) && i + 1 < words.size()
                    && isKnownLabelStart(words.get(i + 1).getText())) {
                break;
            }
            result.add(w);
        }
        return result;
    }

    private boolean isFieldLabel(String text) {
        String cleaned = text.replace(" ", "");
        if (cleaned.matches("\\d{1,2}(자동차등록번호|차종|용도|차명|형식|차대번호|원동기형식|사용본거지|성명|생년월일|법인등록번호|최초등록|길이|너비|높이|총중량|승차정원|최대적재량|배기량|정격출력|기통수|연료|구동축전지|제원관리번호|형식승인번호|검사|연월|번호판|구분).*")) {
            return true;
        }
        String stripped = stripFieldNum(cleaned);
        for (String label : KNOWN_LABELS) {
            if (stripped.startsWith(label.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    private boolean isKnownLabelStart(String text) {
        String cleaned = stripFieldNum(text.replace(" ", ""));
        for (String label : KNOWN_LABELS) {
            if (cleaned.startsWith(label.replace(" ", ""))) return true;
        }
        return false;
    }

    private boolean isDigitsOnly(String text) {
        return text.replace(" ", "").matches("\\d+");
    }

    // ══════════════════════════════════════════════════
    //  anchor 찾기
    // ══════════════════════════════════════════════════

    private OcrWord findAnchorWord(String anchor) {
        return findAnchorWord(anchor, -1);
    }

    /**
     * anchor 단어 찾기
     * @param minY 이 Y좌표 이후에서만 찾기 (-1이면 제한 없음)
     */
    private OcrWord findAnchorWord(String anchor, double minY) {
        return anchorResolver.find(anchor, minY);
    }

    private OcrWord findModelDateAnchorWord() {
        for (String anchor : MODEL_DATE_ANCHORS) {
            OcrWord word = findAnchorWord(anchor);
            if (word != null) return word;
        }
        return null;
    }

    private TextLine findLineOf(OcrWord word) {
        for (TextLine line : lines) {
            if (line.getWords().contains(word)) return line;
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    //  특수 필드 추출
    // ══════════════════════════════════════════════════

    private String extractPlateNumber() {
        return extractPlateNumberScored().value;
    }

    private Extracted extractPlateNumberScored() {
        OcrWord anchor = findAnchorWord("자동차등록번호");
        if (anchor == null) return Extracted.empty();

        List<CandidateValue> candidates = new ArrayList<>();
        TextLine line = findLineOf(anchor);
        if (line != null) {
            List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.30));
            addPlateCandidates(anchor, rightWords, true, 1.0, candidates);

            int lineIdx = lines.indexOf(line);
            if (lineIdx + 1 < lines.size()) {
                addPlateCandidates(anchor, lines.get(lineIdx + 1).getWords(), false, 0.80, candidates);
            }
            if (lineIdx - 1 >= 0) {
                addPlateCandidates(anchor, lines.get(lineIdx - 1).getWords(), false, 0.70, candidates);
            }
        }

        CandidateValue best = chooseBest(candidates, this::isValidPlate);
        if (best == null) {
            return new Extracted(null, anchor, null, 0.0, topCandidateValues(candidates, 5));
        }
        return new Extracted(best.value, anchor, best.valueWord, best.score, topCandidateValues(candidates, 5));
    }

    private String extractVin() {
        return extractVinScored().value;
    }

    private Extracted extractVinScored() {
        OcrWord anchor = findAnchorWord("차대번호");
        if (anchor == null) return Extracted.empty();

        List<CandidateValue> candidates = new ArrayList<>();
        TextLine line = findLineOf(anchor);
        if (line != null) {
            List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.30));
            addVinCandidates(anchor, rightWords, true, 1.0, candidates);

            int lineIdx = lines.indexOf(line);
            if (lineIdx + 1 < lines.size()) {
                addVinCandidates(anchor, lines.get(lineIdx + 1).getWords(), false, 0.80, candidates);
            }
            if (lineIdx - 1 >= 0) {
                addVinCandidates(anchor, lines.get(lineIdx - 1).getWords(), false, 0.65, candidates);
            }
        }

        CandidateValue best = chooseBest(candidates, this::passesVinQuality);
        if (best == null) {
            return new Extracted(null, anchor, null, 0.0, topCandidateValues(candidates, 5));
        }
        return new Extracted(best.value, anchor, best.valueWord, best.score, topCandidateValues(candidates, 5));
    }

    private String extractEngineType() {
        OcrWord anchor = findAnchorWord("원동기형식");
        if (anchor == null) return null;

        TextLine line = findLineOf(anchor);
        if (line == null) return null;

        List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.18));
        List<OcrWord> valueWords = trimAtNextAnchor(rightWords);
        String raw = joinWords(valueWords);

        raw = raw.replaceAll("\\s+\\d{4}-\\d{2}$", "").trim();
        raw = raw.replaceAll("\\s+\\d{4}$", "").trim();

        // 전체가 날짜 패턴이거나 비어있으면 → 인접 줄에서 재검색
        if (raw.isEmpty() || raw.matches("\\d{4}-\\d{2}") || raw.matches("\\d{4}")) {
            int lineIdx = lines.indexOf(line);
            double anchorRightX = anchor.rightX();
            int xMarginLeft = rx(0.04);   // anchor 왼쪽 여유
            int xMarginRight = rx(0.22);  // anchor 오른쪽 탐색 범위
            for (int delta : new int[]{1, -1}) {
                int j = lineIdx + delta;
                if (j < 0 || j >= lines.size()) continue;
                for (OcrWord w : lines.get(j).getWords()) {
                    if (w.getX() < anchorRightX - xMarginLeft) continue;
                    if (w.getX() > anchorRightX + xMarginRight) continue;
                    String t = w.getText().trim();
                    if (t.matches("[A-Z][A-Z0-9]{1,5}") && !t.equals("VIN")) {
                        return t;
                    }
                    if (t.matches("\\d{3}\\s?\\d{3}")) {
                        return t;
                    }
                }
            }
            return null;
        }

        return raw.isEmpty() ? null : raw;
    }

    private void extractModelAndDate(VehicleRegistration reg) {
        String raw = rightOfBounded(0.50, MODEL_DATE_ANCHORS);
        if (raw == null) return;

        Matcher dm = Pattern.compile("^(.+?)\\s+(\\d{4}[-./ ]?\\d{0,2})$").matcher(raw.trim());
        if (dm.find()) {
            reg.setModelCode(dm.group(1).trim());
            String dateStr = dm.group(2).trim().replace(" ", "-");
            Matcher ym = Pattern.compile("(\\d{4})[-./ ]?(\\d{1,2})").matcher(dateStr);
            if (ym.find()) {
                int year = Integer.parseInt(ym.group(1));
                if (isValidModelYear(year)) {
                    String month = pad2(ym.group(2));
                    if (month.matches("0[1-9]|1[0-2]")) {
                        reg.setManufactureYearMonth(year + "-" + month);
                    }
                    reg.setModelYear(year);
                }
            } else {
                Matcher yOnly = Pattern.compile("(\\d{4})").matcher(dateStr);
                if (yOnly.find()) {
                    int year = Integer.parseInt(yOnly.group(1));
                    if (isValidModelYear(year)) {
                        reg.setModelYear(year);
                    }
                }
            }
        } else {
            reg.setModelCode(raw.trim());
            // 날짜가 인접 줄에 있는 경우 fallback
            OcrWord anchor = findModelDateAnchorWord();
            if (anchor != null) {
                TextLine line = findLineOf(anchor);
                int lineIdx = line != null ? lines.indexOf(line) : -1;
                for (int delta : new int[]{1, -1}) {
                    int adj = lineIdx + delta;
                    if (adj < 0 || adj >= lines.size()) continue;
                    for (OcrWord w : lines.get(adj).getWords()) {
                        String t = normalizeDateLike(w.getText().trim()).replaceAll("\\s+", "");
                        Matcher ym2 = Pattern.compile("(\\d{4})[-./]?(\\d{1,2})").matcher(t);
                        if (ym2.find()) {
                            String year = ym2.group(1);
                            String month = pad2(ym2.group(2));
                            int yearInt = Integer.parseInt(year);
                            if (isValidModelYear(yearInt) && month.matches("0[1-9]|1[0-2]")) {
                                reg.setManufactureYearMonth(year + "-" + month);
                            }
                            if (isValidModelYear(yearInt)) {
                                reg.setModelYear(yearInt);
                            }
                            return;
                        }
                        Matcher yOnly = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)").matcher(t);
                        if (yOnly.find()) {
                            int year = Integer.parseInt(yOnly.group(1));
                            if (isValidModelYear(year)) {
                                reg.setModelYear(year);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    private String extractDate(String... anchors) {
        return extractDateScored(null, anchors).value;
    }

    private Extracted extractDateScored(Integer preferredYear, String... anchors) {
        List<CandidateValue> candidates = new ArrayList<>();
        OcrWord usedAnchor = null;

        for (String anchor : anchors) {
            OcrWord word = findAnchorWord(anchor);
            if (word == null) continue;
            if (usedAnchor == null) usedAnchor = word;

            TextLine line = findLineOf(word);
            if (line == null) continue;

            addDateCandidatesFromText(word, line.fullText(), word, true, 1.0, candidates);
            addDateCandidatesFromWords(word, getRightWordsUnlimited(line, word), true, 1.0, candidates);

            int lineIdx = lines.indexOf(line);
            for (int delta : new int[]{1, -1}) {
                int adj = lineIdx + delta;
                if (adj < 0 || adj >= lines.size()) continue;
                TextLine adjLine = lines.get(adj);
                addDateCandidatesFromText(word, adjLine.fullText(), nearestRightWord(adjLine, word), false, 0.80, candidates);
            }

            // old format/회전 사진 대응: anchor 주변 영역에서 날짜 후보 추가
            addDateCandidatesFromNearbyArea(word, candidates);
        }

        CandidateValue best = chooseBestDateCandidate(candidates, preferredYear);
        if (best == null) {
            return new Extracted(null, usedAnchor, null, 0.0, topCandidateValues(candidates, 5));
        }
        return new Extracted(best.value, usedAnchor, best.valueWord, best.score, topCandidateValues(candidates, 5));
    }

    private CandidateValue chooseBestDateCandidate(List<CandidateValue> candidates, Integer preferredYear) {
        return candidates.stream()
                .filter(c -> isValidDate(c.value))
                .max(Comparator.comparingDouble(c -> dateCandidateRank(c, preferredYear)))
                .orElse(null);
    }

    private double dateCandidateRank(CandidateValue candidate, Integer preferredYear) {
        double rank = candidate.score;
        if (preferredYear == null || !isValidModelYear(preferredYear) || candidate.value == null || candidate.value.length() < 4) {
            return rank;
        }
        Integer year = parseNullableInt(candidate.value.substring(0, 4));
        if (year == null) return rank;
        int diff = Math.abs(year - preferredYear);
        return rank - (diff * 0.03);
    }

    private void addDateCandidatesFromNearbyArea(OcrWord anchor, List<CandidateValue> out) {
        if (anchor == null) return;
        double minX = anchor.getX() - rx(0.22);
        double maxX = anchor.rightX() + rx(0.36);
        double minY = anchor.getY() - ry(0.16);
        double maxY = anchor.bottomY() + ry(0.16);

        List<OcrWord> nearby = allWords.stream()
                .filter(w -> w.getX() >= minX && w.getX() <= maxX)
                .filter(w -> w.getY() >= minY && w.getY() <= maxY)
                .sorted(Comparator.comparingDouble(OcrWord::getY).thenComparingDouble(OcrWord::getX))
                .collect(Collectors.toList());

        if (nearby.isEmpty()) return;
        addDateCandidatesFromText(null, joinWords(nearby), nearestWordByDistance(nearby, anchor), false, 0.64, out);

        // 단일/인접 토큰 조합도 탐색 (예: 2011 05 12)
        for (int i = 0; i < nearby.size(); i++) {
            OcrWord w = nearby.get(i);
            addDateCandidatesFromText(null, w.getText(), w, false, 0.62, out);
            if (i + 1 < nearby.size()) {
                String two = w.getText() + " " + nearby.get(i + 1).getText();
                addDateCandidatesFromText(null, two, nearby.get(i + 1), false, 0.60, out);
            }
            if (i + 2 < nearby.size()) {
                String three = w.getText() + " " + nearby.get(i + 1).getText() + " " + nearby.get(i + 2).getText();
                addDateCandidatesFromText(null, three, nearby.get(i + 2), false, 0.58, out);
            }
        }
    }

    private String extractVehicleType() {
        OcrWord anchor = findAnchorWord("차종");
        if (anchor == null) return null;
        TextLine line = findLineOf(anchor);
        if (line == null) return null;

        List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.15));
        List<OcrWord> valueWords = trimAtNextAnchor(rightWords);
        String value = joinWords(valueWords).replaceAll("[\\d①②③]", "").trim();
        return value.isEmpty() ? null : value;
    }

    private String extractOwnerName() {
        OcrWord anchor = findAnchorWord("성명", ry(0.08));

        if (anchor == null) {
            return extractOwnerNameFromOwnerLine();
        }

        TextLine line = findLineOf(anchor);
        if (line == null) return null;

        List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.18));
        List<OcrWord> valueWords = trimAtNextAnchor(rightWords);
        String raw = joinWords(valueWords);

        raw = raw.replaceAll("[*+]?\\(?상품용\\)?[*+]?", "")
                 .replaceAll("\\(명칭\\)", "")
                 .trim();

        if (raw.isEmpty()) {
            int lineIdx = lines.indexOf(line);
            int xRange = rx(0.18);
            for (int delta : new int[]{1, -1}) {
                int adj = lineIdx + delta;
                if (adj < 0 || adj >= lines.size()) continue;
                for (OcrWord w : lines.get(adj).getWords()) {
                    if (w.getX() >= anchor.rightX() - rx(0.005) && w.getX() < anchor.rightX() + xRange) {
                        String t = w.getText().replaceAll("\\(상품용\\)", "").trim();
                        if (t.matches("[가-힣()]{2,}.*") || t.matches("\\(주\\).*")) {
                            return t;
                        }
                    }
                }
            }
        }

        return raw.isEmpty() ? null : raw;
    }

    private String extractOwnerNameFromOwnerLine() {
        for (TextLine line : lines) {
            String compact = line.fullText().replace(" ", "");
            if (compact.contains("소유자")) {
                for (OcrWord w : line.getWords()) {
                    String t = w.getText().trim();
                    if (t.matches("[가-힣]{2,5}\\(상품용\\)")) {
                        return t.replaceAll("\\(상품용\\)", "").trim();
                    }
                    if (t.matches("\\(주\\).+")) {
                        return t.replaceAll("\\(상품용\\)", "").trim();
                    }
                }
                Matcher m = Pattern.compile("([가-힣]{2,5})\\(상품용\\)").matcher(line.fullText());
                if (m.find()) return m.group(1);
                m = Pattern.compile("(\\(주\\)[가-힣A-Za-z]+)").matcher(line.fullText());
                if (m.find()) return m.group(1).replaceAll("\\(상품용\\)", "").trim();
            }
        }
        return null;
    }

    private String extractOwnerId() {
        return extractOwnerIdScored().value;
    }

    private Extracted extractOwnerIdScored() {
        List<CandidateValue> candidates = new ArrayList<>();
        OcrWord primaryAnchor = null;

        String[] anchors = {"생년월일", "법인등록번호", "법인등록", "주민등록번호"};
        double[] weights = {1.0, 0.98, 0.90, 0.88};

        for (int i = 0; i < anchors.length; i++) {
            OcrWord anchor = findAnchorWord(anchors[i], ry(0.08));
            if (anchor == null) continue;
            if (primaryAnchor == null) primaryAnchor = anchor;

            TextLine line = findLineOf(anchor);
            if (line == null) continue;

            addOwnerIdCandidates(anchor, getRightWords(line, anchor, rx(0.34)), true, weights[i], candidates);

            int lineIdx = lines.indexOf(line);
            if (lineIdx + 1 < lines.size()) {
                addOwnerIdCandidates(anchor, lines.get(lineIdx + 1).getWords(), false, weights[i] * 0.83, candidates);
            }
            if (lineIdx - 1 >= 0) {
                addOwnerIdCandidates(anchor, lines.get(lineIdx - 1).getWords(), false, weights[i] * 0.72, candidates);
            }
        }

        for (TextLine line : lines) {
            String compact = line.fullText().replace(" ", "");
            if (!(compact.contains("등록번호") || compact.contains("생년월일")
                    || compact.contains("주민") || compact.contains("법인"))) {
                continue;
            }
            OcrWord pivot = line.getWords().isEmpty() ? null : line.getWords().get(0);
            addOwnerIdCandidates(pivot, line.getWords(), false, 0.62, candidates);
        }

        CandidateValue best = chooseBest(candidates, this::isValidOwnerId);
        if (best == null) {
            return new Extracted(null, primaryAnchor, null, 0.0, topCandidateValues(candidates, 5));
        }
        return new Extracted(best.value, primaryAnchor, best.valueWord, best.score, topCandidateValues(candidates, 5));
    }

    private void addOwnerIdCandidates(
            OcrWord anchor,
            List<OcrWord> words,
            boolean sameLine,
            double weight,
            List<CandidateValue> out
    ) {
        if (words == null || words.isEmpty()) return;

        List<OcrWord> sorted = words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());

        for (OcrWord w : sorted) {
            String ownerId = normalizeOwnerIdCandidate(w.getText());
            if (ownerId == null) continue;
            double regexFit = ownerId.contains("*") ? 0.97 : 1.0;
            out.add(new CandidateValue(ownerId, w, scoreCandidate(anchor, w, sameLine, regexFit, weight), w.getText()));
        }

        for (int i = 0; i < sorted.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < Math.min(i + 4, sorted.size()); j++) {
                sb.append(sorted.get(j).getText());
                String ownerId = normalizeOwnerIdCandidate(sb.toString());
                if (ownerId == null) continue;
                OcrWord pivot = sorted.get(i);
                double regexFit = ownerId.contains("*") ? 0.95 : 0.98;
                out.add(new CandidateValue(
                        ownerId,
                        pivot,
                        scoreCandidate(anchor, pivot, sameLine, regexFit, weight * 0.93),
                        sb.toString()
                ));
            }
        }

        String lineJoined = joinWords(sorted);
        String ownerId = normalizeOwnerIdCandidate(lineJoined);
        if (ownerId != null) {
            OcrWord pivot = nearestRightWord(sorted, anchor);
            double regexFit = ownerId.contains("*") ? 0.94 : 0.96;
            out.add(new CandidateValue(
                    ownerId,
                    pivot,
                    scoreCandidate(anchor, pivot, sameLine, regexFit, weight * 0.88),
                    lineJoined
            ));
        }
    }

    private String normalizeOwnerIdCandidate(String raw) {
        if (raw == null) return null;
        String compact = raw.toUpperCase(Locale.ROOT)
                .replace('●', '*')
                .replace('•', '*')
                .replace('ㆍ', '*')
                .replace('※', '*')
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9A-Z*:-]", "");
        if (compact.isBlank()) return null;

        StringBuilder sb = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c == '-' || c == ':' || c == '*' || c == 'X') {
                sb.append(c == ':' ? '-' : c);
                continue;
            }
            sb.append(swapNumericLikeCharNumericOnly(c));
        }
        String normalized = sb.toString().replaceAll("-{2,}", "-");

        Matcher hyphen = OWNER_ID_WITH_HYPHEN.matcher(normalized);
        if (hyphen.find()) {
            String candidate = hyphen.group(1) + "-" + normalizeOwnerIdSuffix(hyphen.group(2));
            return isValidOwnerId(candidate) ? candidate : null;
        }

        String noDash = normalized.replace("-", "");
        Matcher digits = OWNER_ID_13_DIGITS.matcher(noDash);
        if (digits.find()) {
            String value = digits.group(1);
            String candidate = value.substring(0, 6) + "-" + value.substring(6);
            return isValidOwnerId(candidate) ? candidate : null;
        }

        Matcher masked = OWNER_ID_13_MASKED.matcher(noDash);
        if (masked.find()) {
            String candidate = masked.group(1) + "-" + normalizeOwnerIdSuffix(masked.group(2));
            return isValidOwnerId(candidate) ? candidate : null;
        }
        return null;
    }

    private String normalizeOwnerIdSuffix(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if (c == '*' || c == 'X') {
                sb.append('*');
                continue;
            }
            char swapped = swapNumericLikeCharNumericOnly(c);
            if (Character.isDigit(swapped)) {
                sb.append(swapped);
            }
        }
        if (sb.length() > 7) return sb.substring(0, 7);
        return sb.toString();
    }

    private boolean isValidOwnerId(String ownerId) {
        if (ownerId == null) return false;
        Matcher m = OWNER_ID_CANONICAL.matcher(ownerId);
        if (!m.matches()) return false;

        String front = m.group(1);
        if ("000000".equals(front)) return false;

        String back = m.group(2);
        if (back.chars().allMatch(ch -> ch == '*')) return true;
        return back.chars().allMatch(ch -> Character.isDigit(ch) || ch == '*');
    }

    private String extractAddress() {
        OcrWord anchor = findAnchorWord("사용본거지", ry(0.08));
        if (anchor == null) return null;

        TextLine line = findLineOf(anchor);
        if (line == null) return null;

        List<OcrWord> rightWords = getRightWordsUnlimited(line, anchor);
        String address = joinWords(rightWords);

        int lineIdx = lines.indexOf(line);
        if (lineIdx >= 0 && lineIdx + 1 < lines.size()) {
            TextLine nextLine = lines.get(lineIdx + 1);
            String nextCompact = nextLine.fullText().replace(" ", "");
            if (!nextCompact.contains("소유자") && !nextCompact.contains("성명")
                    && !nextCompact.contains("생년월일") && !nextCompact.contains("법인등록번호")
                    && !nextCompact.contains("자동차관리법") && !nextCompact.contains("등록하였음")) {
                address += " " + nextLine.fullText().trim();
            }
        }
        return address.isEmpty() ? null : address.trim();
    }

    private String extractUsage() {
        String raw = rightOfBounded(0.15, "용도");
        if (raw != null) {
            if (raw.contains("자가용")) return "자가용";
            if (raw.contains("사업용")) return "사업용";
            return raw.trim();
        }
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("용도") && text.contains("자가용")) return "자가용";
            if (text.contains("용도") && text.contains("사업용")) return "사업용";
        }
        return null;
    }

    // ── 제원 필드 ──

    private String extractDimension(String... anchors) {
        for (String anchor : anchors) {
            OcrWord anchorWord = findAnchorWord(anchor, specMinY());
            if (anchorWord == null) continue;

            TextLine line = findLineOf(anchorWord);
            if (line == null) continue;

            List<OcrWord> rightWords = getRightWords(line, anchorWord, rx(0.18));
            for (OcrWord w : rightWords) {
                Matcher m = Pattern.compile("(\\d{3,5})").matcher(w.getText());
                if (m.find()) {
                    int val = Integer.parseInt(m.group(1));
                    if (val >= 100 && val <= 6000) return val + "mm";
                }
            }
            // 값이 아래 줄에 있는 경우 (old format)
            int lineIdx = lines.indexOf(line);
            for (int delta : new int[]{1, -1}) {
                int adj = lineIdx + delta;
                if (adj < 0 || adj >= lines.size()) continue;
                for (OcrWord w : lines.get(adj).getWords()) {
                    if (Math.abs(w.getX() - anchorWord.getX()) > rx(0.25)) continue;
                    Matcher m = Pattern.compile("(\\d{3,5})").matcher(w.getText());
                    if (m.find()) {
                        int val = Integer.parseInt(m.group(1));
                        if (val >= 100 && val <= 6000) return val + "mm";
                    }
                }
            }
        }
        // fallback: 제원 영역에서 "숫자 + mm" 패턴 직접 찾기
        if (specSectionY < Double.MAX_VALUE) {
            String targetLabel = anchors[0]; // 첫 번째 anchor 이름
            for (TextLine line : lines) {
                if (line.avgY() < specMinY()) continue;
                String text = line.fullText();
                // 라인에 anchor의 마지막 글자가 포함되고 mm이 포함된 경우
                if (text.contains("mm") || text.contains("mn")) {
                    Matcher m = Pattern.compile("(?:" + targetLabel.charAt(targetLabel.length() - 1) + "|"
                            + targetLabel + ")\\s*(\\d{3,5})\\s*m").matcher(text);
                    if (m.find()) {
                        int val = Integer.parseInt(m.group(1));
                        if (val >= 100 && val <= 6000) return val + "mm";
                    }
                }
            }
        }
        return null;
    }

    private String extractKg(String... anchors) {
        return extractKgScored(KgFieldType.WEIGHT, anchors).value;
    }

    private Extracted extractKgScored(KgFieldType fieldType, String... anchors) {
        return extractKgScored(fieldType, null, anchors);
    }

    private Extracted extractKgScored(KgFieldType fieldType, Integer referenceWeightKg, String... anchors) {
        List<CandidateValue> candidates = new ArrayList<>();
        OcrWord primaryAnchor = null;

        for (String anchor : anchors) {
            OcrWord anchorWord = findAnchorWord(anchor, specMinY());
            if (anchorWord == null && anchor.equals("총중량")) {
                anchorWord = findAnchorWord("종중량", specMinY());
                if (anchorWord == null) anchorWord = findAnchorWord("총층층", specMinY());
            }
            if (anchorWord == null) continue;
            if (primaryAnchor == null) primaryAnchor = anchorWord;

            TextLine line = findLineOf(anchorWord);
            if (line == null) continue;

            addKgCandidates(anchorWord, getRightWords(line, anchorWord, rx(0.55)), true, 1.0, fieldType, candidates);

            int lineIdx = lines.indexOf(line);
            if (lineIdx + 1 < lines.size()) {
                TextLine nextLine = lines.get(lineIdx + 1);
                if (!hasCompetingKgAnchor(nextLine, fieldType)) {
                    addKgCandidates(anchorWord, nextLine.getWords(), false, 0.82, fieldType, candidates);
                }
            }
            if (lineIdx - 1 >= 0) {
                TextLine prevLine = lines.get(lineIdx - 1);
                if (!hasCompetingKgAnchor(prevLine, fieldType)) {
                    addKgCandidates(anchorWord, prevLine.getWords(), false, 0.72, fieldType, candidates);
                }
            }
        }

        Predicate<String> validator = v -> isValidKgValue(v, fieldType);

        CandidateValue best = null;
        if (fieldType == KgFieldType.MAX_LOAD && referenceWeightKg != null && referenceWeightKg > 0) {
            best = candidates.stream()
                    .sorted(Comparator.comparingDouble((CandidateValue c) -> c.score).reversed())
                    .filter(c -> validator.test(c.value))
                    .filter(c -> {
                        Integer kg = parseKgInt(c.value);
                        return kg != null && kg <= referenceWeightKg;
                    })
                    .findFirst()
                    .orElse(null);
        }
        if (best == null) {
            best = chooseBest(candidates, validator);
        }
        if (best == null) {
            return new Extracted(null, primaryAnchor, null, 0.0, topCandidateValues(candidates, 5));
        }
        return new Extracted(best.value, primaryAnchor, best.valueWord, best.score, topCandidateValues(candidates, 5));
    }

    private void addKgCandidates(
            OcrWord anchor,
            List<OcrWord> words,
            boolean sameLine,
            double weight,
            KgFieldType fieldType,
            List<CandidateValue> out
    ) {
        if (words == null || words.isEmpty()) return;

        List<OcrWord> sorted = words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());

        for (OcrWord w : sorted) {
            ParsedKg parsed = parseKgCandidate(w.getText());
            if (parsed == null || !isValidKgValue(parsed.value, fieldType)) continue;
            String normalized = parsed.value + "kg";
            double regexFit = parsed.hasKgUnit ? 1.0 : 0.84;
            out.add(new CandidateValue(
                    normalized,
                    w,
                    scoreCandidate(anchor, w, sameLine, regexFit, weight),
                    w.getText()
            ));
        }

        for (int i = 0; i < sorted.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < Math.min(i + 4, sorted.size()); j++) {
                sb.append(sorted.get(j).getText());
                ParsedKg parsed = parseKgCandidate(sb.toString());
                if (parsed == null || !isValidKgValue(parsed.value, fieldType)) continue;

                String normalized = parsed.value + "kg";
                OcrWord pivot = sorted.get(i);
                double regexFit = parsed.hasKgUnit ? 0.98 : 0.80;
                out.add(new CandidateValue(
                        normalized,
                        pivot,
                        scoreCandidate(anchor, pivot, sameLine, regexFit, weight * 0.92),
                        sb.toString()
                ));
            }
        }
    }

    private ParsedKg parseKgCandidate(String raw) {
        if (raw == null) return null;
        String upper = raw.toUpperCase(Locale.ROOT)
                .replace("㎏", "KG")
                .replace("ＫＧ", "KG")
                .replace(",", "")
                .replaceAll("\\s+", "");
        if (upper.isBlank() || upper.contains("/")) return null;

        boolean hasKgUnit = upper.contains("KG") || upper.contains("K9")
                || upper.contains("KQ") || upper.contains("K8");

        String compact = upper.replaceAll("[^0-9A-Z]", "");
        StringBuilder normalized = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c == 'K' || c == 'G') {
                normalized.append(c);
            } else {
                normalized.append(swapNumericLikeCharNumericOnly(c));
            }
        }

        Matcher m = Pattern.compile("(\\d{2,6})").matcher(normalized.toString());
        int best = -1;
        while (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (value > best) best = value;
        }
        // "0" 단독 — 승용차 최대적재량 전용
        if (best < 0 && normalized.toString().matches(".*(?:^|[^0-9])0(?:[^0-9]|$).*")) {
            best = 0;
        }
        if (best < 0) return null;
        return new ParsedKg(best, hasKgUnit);
    }

    private boolean isValidKgValue(String value, KgFieldType fieldType) {
        if (value == null || !value.matches("\\d{1,6}kg")) return false;
        int n = Integer.parseInt(value.substring(0, value.length() - 2));
        return isValidKgValue(n, fieldType);
    }

    private boolean isValidKgValue(int value, KgFieldType fieldType) {
        if (fieldType == KgFieldType.MAX_LOAD) {
            return value >= 0 && value <= 60000;
        }
        return value >= 500 && value <= 100000;
    }

    private boolean hasCompetingKgAnchor(TextLine line, KgFieldType fieldType) {
        if (line == null) return false;
        String compact = line.fullText().replace(" ", "");
        if (fieldType == KgFieldType.WEIGHT) {
            return compact.contains("최대적재량") || compact.contains("적재량");
        }
        return false;
    }

    private Integer parseKgInt(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("(\\d{1,6})").matcher(raw);
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1));
    }

    private String extractSeating() {
        OcrWord anchor = findAnchorWord("승차정원", specMinY());
        if (anchor == null) anchor = findAnchorWord("정원", specMinY());
        if (anchor == null) return null;
        TextLine line = findLineOf(anchor);
        if (line == null) return null;

        List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.30));
        for (OcrWord w : rightWords) {
            String clean = w.getText().replaceAll("[VLv]", "");
            Matcher m = Pattern.compile("(\\d+)").matcher(clean);
            if (m.find()) {
                int val = Integer.parseInt(m.group(1));
                if (val >= 1 && val <= 50) return val + "명";
            }
        }
        return null;
    }

    private Integer extractDisplacementInt() {
        return parseNullableInt(extractDisplacementScored().value);
    }

    private Extracted extractDisplacementScored() {
        OcrWord anchor = findAnchorWord("배기량", specMinY());
        List<CandidateValue> candidates = new ArrayList<>();

        if (anchor != null) {
            TextLine line = findLineOf(anchor);
            if (line != null) {
                addDisplacementCandidates(anchor, getRightWords(line, anchor, rx(0.40)), true, 1.0, candidates);

                int lineIdx = lines.indexOf(line);
                for (int j = lineIdx + 1; j <= Math.min(lineIdx + 2, lines.size() - 1); j++) {
                    TextLine nextLine = lines.get(j);
                    addDisplacementCandidates(anchor, nextLine.getWords(), false, 0.75, candidates);
                }
            }
        }

        // fallback: 배기량 anchor가 깨진 케이스를 위해 제원영역에서 cc 패턴 탐색
        for (TextLine line : lines) {
            if (line.avgY() < specMinY()) continue;
            String text = line.fullText();
            if (text.contains("/")) continue; // 정격출력 패턴 제외
            String lower = text.toLowerCase(Locale.ROOT);
            boolean hasCcLike = lower.contains("cc") || text.contains("<");
            boolean batteryLine = text.contains("구동축전지");
            if (!hasCcLike && !batteryLine) continue;

            // 날짜(예: 2024-01-10) 오탐 방지를 위해 cc/< 표식이 있는 숫자만 인정
            if (!hasCcLike) continue;
            Matcher m = Pattern.compile("(\\d{3,5})\\s*(?:cc|<)", Pattern.CASE_INSENSITIVE).matcher(text);
            while (m.find()) {
                String val = m.group(1);
                if (!isValidDisplacement(val)) continue;
                OcrWord pivot = line.getWords().isEmpty() ? null : line.getWords().get(0);
                double base = hasCcLike ? 0.58 : 0.50;
                double score = clamp01(base + (pivot == null ? 0 : pivot.getConfidence() * 0.12));
                candidates.add(new CandidateValue(val, pivot, score, text));
            }
        }

        CandidateValue best = chooseBest(candidates, this::isValidDisplacement);
        if (best == null) {
            return new Extracted(null, anchor, null, 0.0, topCandidateValues(candidates, 5));
        }
        return new Extracted(best.value, anchor, best.valueWord, best.score, topCandidateValues(candidates, 5));
    }

    private String extractPower() {
        OcrWord anchor = findAnchorWord("정격출력", specMinY());
        if (anchor == null) anchor = findAnchorWord("정격", specMinY());
        if (anchor == null) return null;
        TextLine line = findLineOf(anchor);
        if (line == null) return null;

        List<OcrWord> rightWords = getRightWords(line, anchor, rx(0.25));
        String combined = joinWords(rightWords);
        Matcher m = Pattern.compile("(\\d{2,4}/\\d{3,5})").matcher(combined);
        if (m.find()) return m.group(1);

        // 인접 줄에서 찾기
        int lineIdx = lines.indexOf(line);
        for (int delta : new int[]{1, -1}) {
            int adj = lineIdx + delta;
            if (adj < 0 || adj >= lines.size()) continue;
            String adjText = lines.get(adj).fullText();
            m = Pattern.compile("(\\d{2,4}/\\d{3,5})").matcher(adjText);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String extractFuelType() {
        for (TextLine line : lines) {
            // 유의사항/설명 텍스트 제외 (잘못된 매칭 방지)
            String text = line.fullText();
            String compact = text.replace(" ", "");
            if (compact.contains("유의사항") || compact.contains("말합니다")
                    || compact.contains("말하며") || compact.contains("경우")) continue;

            if (text.contains("연료") || text.contains("휘발유") || text.contains("경유")
                    || text.contains("엘피지")) {
                if (text.contains("하이브리드")) {
                    Matcher m = Pattern.compile("하이브리드\\([^)]+\\)").matcher(text);
                    if (m.find()) return m.group();
                    return "하이브리드";
                }
                if (text.contains("휘발유")) return text.contains("무연") ? "휘발유(무연)" : "휘발유";
                if (text.contains("경유")) return "경유";
                if (text.contains("LPG") || text.contains("엘피지")) return "LPG";
                if (text.contains("전기")) return "전기";
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    //  유틸리티
    // ══════════════════════════════════════════════════

    private QualityGate evaluateQualityGate() {
        if (allWords.isEmpty() || lines.isEmpty()) {
            return new QualityGate(false, 0.0, "OCR word가 없습니다. 재촬영 또는 OCR 재시도가 필요합니다.");
        }

        double avgConfidence = clamp01(
                allWords.stream().mapToDouble(OcrWord::getConfidence).average().orElse(0.0)
        );

        List<String> coreAnchors = List.of("자동차등록번호", "차대번호", "최초등록일", "승차정원", "제원");
        int found = 0;
        for (String a : coreAnchors) {
            if (findAnchorWord(a) != null) found++;
        }
        double anchorScore = found / (double) coreAnchors.size();

        double lineConsistency = estimateLineConsistency();
        double score = clamp01(avgConfidence * 0.45 + anchorScore * 0.35 + lineConsistency * 0.20);

        boolean passed = score >= 0.58 && anchorScore >= 0.40;
        String reason = passed
                ? "OK"
                : String.format(
                Locale.ROOT,
                "문서 품질 낮음(score=%.3f, anchor=%.3f, conf=%.3f, line=%.3f). 재촬영/보정 경로로 전송 필요",
                score, anchorScore, avgConfidence, lineConsistency
        );
        return new QualityGate(passed, score, reason);
    }

    private double estimateLineConsistency() {
        if (lines.isEmpty()) return 0.0;
        List<Double> perLine = new ArrayList<>();
        for (TextLine line : lines) {
            List<OcrWord> ws = line.getWords();
            if (ws.size() < 2) continue;
            double min = ws.stream().mapToDouble(OcrWord::centerY).min().orElse(0);
            double max = ws.stream().mapToDouble(OcrWord::centerY).max().orElse(0);
            double spread = max - min;
            double allowed = Math.max(line.avgHeight() * 1.25, ry(0.01));
            double score = clamp01(1.0 - (spread / (allowed * 2.0)));
            perLine.add(score);
        }
        if (perLine.isEmpty()) return 0.55;
        return clamp01(perLine.stream().mapToDouble(Double::doubleValue).average().orElse(0.55));
    }

    private void applyPostValidation(VehicleRegistration reg) {
        boolean yearOnlyFormat = findAnchorWord("형식및연식") != null
                || findAnchorWord("형식및연식모델연도") != null
                || findAnchorWord("형식및연식모델년도") != null
                || findAnchorWord("형식및모델연도") != null;

        // 1순위: VIN 10번째 자리에서 연식 추출 (가장 신뢰도 높음)
        Integer vinYear = inferModelYearFromVin(reg.getVin());
        if (isValidModelYear(vinYear)) {
            reg.setModelYear(vinYear);
        }
        // 2순위: OCR 파싱 결과 (VIN에서 못 뽑았을 때만)
        if (!isValidModelYear(reg.getModelYear())
                && isValidManufactureYearMonth(reg.getManufactureYearMonth())) {
            reg.setModelYear(Integer.parseInt(reg.getManufactureYearMonth().substring(0, 4)));
        }

        List<String> issues = new ArrayList<>();
        if (!isValidPlate(reg.getVehicleNo())) issues.add("vehicleNo");
        if (!passesVinQuality(reg.getVin())) issues.add("vin");
        if (!isValidDate(reg.getFirstRegistratedAt())) issues.add("firstRegistratedAt");
        if (reg.getVehicleUse() == null || reg.getVehicleUse().isBlank()) issues.add("vehicleUse");
        if (!isValidModelYear(reg.getModelYear())) issues.add("modelYear");
        if (!isValidManufactureYearMonth(reg.getManufactureYearMonth())
                && !(yearOnlyFormat && isValidModelYear(reg.getModelYear()))) {
            issues.add("manufactureYearMonth");
        }
        if (isValidModelYear(reg.getModelYear()) && isValidManufactureYearMonth(reg.getManufactureYearMonth())) {
            int y1 = reg.getModelYear();
            int y2 = Integer.parseInt(reg.getManufactureYearMonth().substring(0, 4));
            if (Math.abs(y1 - y2) > 1) {
                issues.add("yearConsistency");
            }
        }

        if (!issues.isEmpty()) {
            reg.setNeedsRetry(true);
            String msg = "핵심 필드 검증 실패: " + String.join(", ", issues) + " (2순위 후보/재촬영 필요)";
            if (reg.getQualityReason() == null || reg.getQualityReason().isBlank() || "OK".equals(reg.getQualityReason())) {
                reg.setQualityReason(msg);
            } else {
                reg.setQualityReason(reg.getQualityReason() + " | " + msg);
            }
        }
    }

    private void putEvidence(String field, Extracted extracted) {
        if (extracted == null) return;
        evidenceMap.put(field, new FieldEvidence(
                extracted.value,
                round4(extracted.confidence),
                extracted.anchorWord != null ? extracted.anchorWord.getText() : null,
                toBox(extracted.anchorWord),
                extracted.valueWord != null ? extracted.valueWord.getText() : extracted.value,
                toBox(extracted.valueWord),
                extracted.candidates
        ));
    }

    private void putSimpleEvidence(String field, String value, OcrWord anchorWord) {
        double base = value == null ? 0.0 : (anchorWord == null ? 0.55 : clamp01(0.55 + anchorWord.getConfidence() * 0.30));
        evidenceMap.put(field, new FieldEvidence(
                value,
                round4(base),
                anchorWord != null ? anchorWord.getText() : null,
                toBox(anchorWord),
                value,
                null,
                value == null ? Collections.emptyList() : List.of(value)
        ));
    }

    private EvidenceBox toBox(OcrWord word) {
        if (word == null) return null;
        double nx = word.getX() / docWidth;
        double ny = word.getY() / docHeight;
        double nw = word.getWidth() / docWidth;
        double nh = word.getHeight() / docHeight;
        return new EvidenceBox(
                round4(word.getX()),
                round4(word.getY()),
                round4(word.getWidth()),
                round4(word.getHeight()),
                round4(nx),
                round4(ny),
                round4(nw),
                round4(nh)
        );
    }

    private void addPlateCandidates(OcrWord anchor, List<OcrWord> words, boolean sameLine, double weight, List<CandidateValue> out) {
        List<OcrWord> sorted = words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());

        for (OcrWord w : sorted) {
            String plate = normalizePlate(w.getText());
            if (plate == null) continue;
            double score = scoreCandidate(anchor, w, sameLine, 1.0, weight);
            out.add(new CandidateValue(plate, w, score, w.getText()));
        }

        String joined = joinWords(sorted);
        String plate = normalizePlate(joined);
        if (plate != null) {
            OcrWord pivot = nearestRightWord(sorted, anchor);
            double score = scoreCandidate(anchor, pivot, sameLine, 0.95, weight * 0.95);
            out.add(new CandidateValue(plate, pivot, score, joined));
        }
    }

    private void addVinCandidates(OcrWord anchor, List<OcrWord> words, boolean sameLine, double weight, List<CandidateValue> out) {
        List<OcrWord> sorted = words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());

        for (OcrWord w : sorted) {
            for (String vin : normalizeVinCandidates(w.getText())) {
                double regexFit = vinRegexFit(vin, 1.00, 0.90);
                out.add(new CandidateValue(vin, w, scoreCandidate(anchor, w, sameLine, regexFit, weight), w.getText()));
            }
        }

        for (int i = 0; i < sorted.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < Math.min(i + 4, sorted.size()); j++) {
                sb.append(sorted.get(j).getText());
                for (String vin : normalizeVinCandidates(sb.toString())) {
                    OcrWord pivot = sorted.get(i);
                    double regexFit = vinRegexFit(vin, 0.98, 0.88);
                    out.add(new CandidateValue(
                            vin,
                            pivot,
                            scoreCandidate(anchor, pivot, sameLine, regexFit, weight * 0.93),
                            sb.toString()
                    ));
                }
            }
        }

        for (String full : normalizeVinCandidates(joinWords(sorted))) {
            OcrWord pivot = nearestRightWord(sorted, anchor);
            double regexFit = vinRegexFit(full, 0.96, 0.86);
            out.add(new CandidateValue(
                    full,
                    pivot,
                    scoreCandidate(anchor, pivot, sameLine, regexFit, weight * 0.90),
                    joinWords(sorted)
            ));
        }
    }

    private double vinRegexFit(String vin, double checkDigitValidBase, double checkDigitInvalidBase) {
        if (vin == null || vin.length() != 17) return clamp01(checkDigitInvalidBase * 0.85);
        double fit = isVinCheckDigitValid(vin) ? checkDigitValidBase : checkDigitInvalidBase;

        // 비북미 VIN에서는 체크디지트보다 일련번호(뒤 6자리) 숫자 연속성이 더 실전적으로 안정적이다.
        int tailDigits = 0;
        for (int i = 11; i < 17; i++) {
            if (Character.isDigit(vin.charAt(i))) tailDigits++;
        }
        fit += (tailDigits - 4) * 0.06; // 4자리 기준, 6자리면 +0.12
        if (!isNorthAmericaVin(vin) && tailDigits == 6) fit += 0.02;

        return clamp01(fit);
    }

    private void addDateCandidatesFromWords(OcrWord anchor, List<OcrWord> words, boolean sameLine, double weight, List<CandidateValue> out) {
        addDateCandidatesFromText(anchor, joinWords(words), nearestRightWord(words, anchor), sameLine, weight, out);
    }

    private void addDateCandidatesFromText(
            OcrWord anchor,
            String text,
            OcrWord pivot,
            boolean sameLine,
            double weight,
            List<CandidateValue> out
    ) {
        Matcher m = DATE_YMD.matcher(text);
        while (m.find()) {
            String date = normalizeDate(m.group(1), m.group(2), m.group(3));
            if (!isValidDate(date)) continue;
            double score = scoreCandidate(anchor, pivot, sameLine, 1.0, weight);
            out.add(new CandidateValue(date, pivot, score, m.group()));
        }

        Matcher ym = DATE_YM.matcher(text);
        while (ym.find()) {
            String date = normalizeDate(ym.group(1), ym.group(2), "01");
            if (!isValidDate(date)) continue;
            double score = scoreCandidate(anchor, pivot, sameLine, 0.75, weight * 0.70);
            out.add(new CandidateValue(date, pivot, score, ym.group()));
        }

        // OCR 혼동 보정 (예: O->0, B->8) 후 날짜 재매칭
        String normalizedDateText = normalizeDateLike(text);
        if (!normalizedDateText.equals(text)) {
            Matcher m2 = DATE_YMD.matcher(normalizedDateText);
            while (m2.find()) {
                String date = normalizeDate(m2.group(1), m2.group(2), m2.group(3));
                if (!isValidDate(date)) continue;
                double score = scoreCandidate(anchor, pivot, sameLine, 0.92, weight * 0.92);
                out.add(new CandidateValue(date, pivot, score, m2.group()));
            }
            Matcher ym2 = DATE_YM.matcher(normalizedDateText);
            while (ym2.find()) {
                String date = normalizeDate(ym2.group(1), ym2.group(2), "01");
                if (!isValidDate(date)) continue;
                double score = scoreCandidate(anchor, pivot, sameLine, 0.72, weight * 0.62);
                out.add(new CandidateValue(date, pivot, score, ym2.group()));
            }
        }
    }

    private void addDisplacementCandidates(OcrWord anchor, List<OcrWord> words, boolean sameLine, double weight, List<CandidateValue> out) {
        List<OcrWord> sorted = words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .collect(Collectors.toList());

        for (OcrWord w : sorted) {
            if (w.getText().contains("/")) continue;
            if (w.getText().matches(".*\\d{4}-\\d{1,2}.*")) continue;
            if (w.getText().contains("-")
                    && !w.getText().toLowerCase(Locale.ROOT).contains("cc")
                    && !w.getText().contains("<")) continue;
            String normalized = normalizeNumericOnlyText(w.getText());
            Matcher m = Pattern.compile("(\\d{3,5})(?:\\s*cc)?", Pattern.CASE_INSENSITIVE).matcher(normalized);
            while (m.find()) {
                String value = m.group(1);
                if (!isValidDisplacement(value)) continue;
                double regexFit = w.getText().toLowerCase(Locale.ROOT).contains("cc") ? 1.0 : 0.82;
                out.add(new CandidateValue(value, w, scoreCandidate(anchor, w, sameLine, regexFit, weight), w.getText()));
            }
        }

        String combined = normalizeNumericOnlyText(joinWords(sorted));
        if (!combined.contains("/")) {
            Matcher m = Pattern.compile("(\\d{3,5})\\s*cc", Pattern.CASE_INSENSITIVE).matcher(combined);
            while (m.find()) {
                String value = m.group(1);
                if (!isValidDisplacement(value)) continue;
                OcrWord pivot = nearestRightWord(sorted, anchor);
                out.add(new CandidateValue(value, pivot, scoreCandidate(anchor, pivot, sameLine, 1.0, weight * 0.92), m.group()));
            }
        }
    }

    private CandidateValue chooseBest(List<CandidateValue> candidates, Predicate<String> validator) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble((CandidateValue c) -> c.score).reversed())
                .filter(c -> validator.test(c.value))
                .findFirst()
                .orElse(null);
    }

    private List<String> topCandidateValues(List<CandidateValue> candidates, int limit) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble((CandidateValue c) -> c.score).reversed())
                .map(c -> c.value + "@" + round4(c.score))
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private OcrWord nearestRightWord(List<OcrWord> words, OcrWord anchor) {
        if (words == null || words.isEmpty()) return null;
        if (anchor == null) return words.get(0);
        double minX = anchor.rightX() - rx(0.01);
        return words.stream()
                .filter(w -> w.getX() >= minX)
                .min(Comparator.comparingDouble(OcrWord::getX))
                .orElse(words.get(0));
    }

    private OcrWord nearestRightWord(TextLine line, OcrWord anchor) {
        if (line == null) return null;
        return nearestRightWord(
                line.getWords().stream().sorted(Comparator.comparingDouble(OcrWord::getX)).collect(Collectors.toList()),
                anchor
        );
    }

    private OcrWord nearestWordByDistance(List<OcrWord> words, OcrWord anchor) {
        if (words == null || words.isEmpty()) return null;
        if (anchor == null) return words.get(0);
        double ax = anchor.centerX();
        double ay = anchor.centerY();
        return words.stream()
                .min(Comparator.comparingDouble(w -> {
                    double dx = w.centerX() - ax;
                    double dy = w.centerY() - ay;
                    return dx * dx + dy * dy;
                }))
                .orElse(words.get(0));
    }

    private double scoreCandidate(OcrWord anchor, OcrWord valueWord, boolean sameLine, double regexFit, double weight) {
        double distanceScore = 0.5;
        if (anchor != null && valueWord != null) {
            double dx = valueWord.getX() - anchor.rightX();
            if (dx < -rx(0.03)) {
                distanceScore = 0.05;
            } else {
                double maxRange = Math.max(rx(0.45), 1);
                distanceScore = clamp01(1.0 - Math.min(Math.max(dx, 0), maxRange) / maxRange);
            }
        }
        double conf = valueWord == null ? 0.55 : clamp01(valueWord.getConfidence());
        double lineScore = sameLine ? 1.0 : 0.72;
        return round4(clamp01((0.42 * regexFit + 0.25 * distanceScore + 0.18 * lineScore + 0.15 * conf) * weight));
    }

    private boolean isValidPlate(String v) {
        if (v == null) return false;
        return PLATE.matcher(v).matches();
    }

    private String normalizePlate(String raw) {
        if (raw == null) return null;
        String compact = raw.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9A-Z가-힣]", "");

        int hangulIdx = -1;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c >= '가' && c <= '힣') {
                hangulIdx = i;
                break;
            }
        }

        String candidate = compact;
        if (hangulIdx >= 0) {
            String prefix = normalizeNumericOnlyText(compact.substring(0, hangulIdx));
            String suffix = normalizeNumericOnlyText(compact.substring(hangulIdx + 1));
            candidate = prefix + compact.charAt(hangulIdx) + suffix;
        } else {
            candidate = normalizeNumericOnlyText(compact);
        }

        Matcher m = PLATE.matcher(candidate);
        if (!m.find()) return null;
        return m.group();
    }

    private boolean isValidVin(String v) {
        if (v == null) return false;
        return VIN.matcher(v).matches();
    }

    private boolean passesVinQuality(String vin) {
        if (!isValidVin(vin)) return false;
        if (isNorthAmericaVin(vin)) {
            // 북미권 VIN은 체크디지트 엄격 검증
            return isVinCheckDigitValid(vin);
        }

        // 비북미 VIN은 체크디지트가 엄밀하지 않은 케이스가 있어 구조 규칙 중심으로 허용
        int alphaCount = 0;
        int digitCount = 0;
        for (int i = 0; i < vin.length(); i++) {
            if (Character.isLetter(vin.charAt(i))) alphaCount++;
            if (Character.isDigit(vin.charAt(i))) digitCount++;
        }
        int tailDigits = 0;
        for (int i = 11; i < 17; i++) {
            if (Character.isDigit(vin.charAt(i))) tailDigits++;
        }
        // 뒤 6자리 중 최소 4자리가 숫자면 허용 (BMW 등 유럽 VIN은 알파벳 포함 가능)
        return alphaCount >= 3 && digitCount >= 5 && tailDigits >= 4;
    }

    private boolean isNorthAmericaVin(String vin) {
        if (vin == null || vin.isEmpty()) return false;
        char c = vin.charAt(0);
        return c >= '1' && c <= '5';
    }

    private String normalizeVin(String raw) {
        List<String> candidates = normalizeVinCandidates(raw);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private List<String> normalizeVinCandidates(String raw) {
        if (raw == null) return Collections.emptyList();
        String compact = raw.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "")
                .replace('I', '1')
                .replace('O', '0')
                .replace('Q', '0');

        Set<String> baseVariants = new LinkedHashSet<>();
        baseVariants.add(compact);
        addCharSwapVariants(baseVariants, compact, 'B', '8', 6, 512);

        Set<String> vinSet = new LinkedHashSet<>();
        Map<String, Integer> vinSwapCost = new HashMap<>();
        for (String candidate : baseVariants) {
            Matcher m = VIN.matcher(candidate);
            while (m.find()) {
                String vin = m.group();
                vinSet.add(vin);
                int swapCost = estimateSwapCost(compact, m.start(), vin);
                vinSwapCost.merge(vin, swapCost, Math::min);
            }
        }

        List<String> vins = new ArrayList<>(vinSet);
        vins.sort((a, b) -> {
            int scoreCmp = Integer.compare(vinSortScore(b), vinSortScore(a));
            if (scoreCmp != 0) return scoreCmp;
            int swapCmp = Integer.compare(vinSwapCost.getOrDefault(a, 99), vinSwapCost.getOrDefault(b, 99));
            if (swapCmp != 0) return swapCmp;
            return 0;
        });
        return vins;
    }

    private int vinSortScore(String vin) {
        if (vin == null || vin.length() != 17) return Integer.MIN_VALUE / 4;
        int score = 0;
        // 북미 VIN(1~5)은 체크디지트 신뢰도가 매우 높지만,
        // 비북미 VIN은 OCR 보정(B/8 등) 우선순위를 너무 눌러버리지 않도록 가중치를 낮춘다.
        if (isVinCheckDigitValid(vin)) {
            score += isNorthAmericaVin(vin) ? 1000 : 120;
        }

        // VIN 뒤 6자리(생산 일련번호)는 숫자일 확률이 높아 B/8 혼동 보정 우선순위에 활용
        int tailDigits = 0;
        for (int i = 11; i < 17; i++) {
            if (Character.isDigit(vin.charAt(i))) tailDigits++;
        }
        score += tailDigits * 130;
        score -= (6 - tailDigits) * 180;

        char check = vin.charAt(8);
        if (Character.isDigit(check) || check == 'X') score += 20;

        return score;
    }

    private int estimateSwapCost(String compact, int start, String vin) {
        if (compact == null || vin == null) return 99;
        int end = start + vin.length();
        if (start < 0 || end > compact.length()) return 99;
        int diff = 0;
        for (int i = 0; i < vin.length(); i++) {
            if (compact.charAt(start + i) != vin.charAt(i)) diff++;
        }
        return diff;
    }

    private void addCharSwapVariants(
            Set<String> variants,
            String src,
            char a,
            char b,
            int maxSwapCount,
            int maxVariants
    ) {
        if (src == null || src.isBlank() || maxSwapCount <= 0 || maxVariants <= 0) return;

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == a || c == b) positions.add(i);
        }
        if (positions.isEmpty()) return;

        char[] chars = src.toCharArray();
        buildSwapVariants(variants, chars, positions, 0, 0, maxSwapCount, maxVariants, a, b);
    }

    private void buildSwapVariants(
            Set<String> variants,
            char[] chars,
            List<Integer> positions,
            int idx,
            int swapCount,
            int maxSwapCount,
            int maxVariants,
            char a,
            char b
    ) {
        if (variants.size() >= maxVariants) return;

        if (idx >= positions.size()) {
            if (swapCount > 0) variants.add(new String(chars));
            return;
        }

        buildSwapVariants(variants, chars, positions, idx + 1, swapCount, maxSwapCount, maxVariants, a, b);

        if (variants.size() >= maxVariants || swapCount >= maxSwapCount) return;

        int pos = positions.get(idx);
        char original = chars[pos];
        chars[pos] = (original == a) ? b : a;
        buildSwapVariants(variants, chars, positions, idx + 1, swapCount + 1, maxSwapCount, maxVariants, a, b);
        chars[pos] = original;
    }

    private boolean isVinCheckDigitValid(String vin) {
        if (vin == null || vin.length() != 17 || !VIN.matcher(vin).matches()) return false;

        char checkChar = vin.charAt(8);
        if (!Character.isDigit(checkChar) && checkChar != 'X') {
            return false;
        }

        int[] weights = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < vin.length(); i++) {
            int value = vinCharValue(vin.charAt(i));
            if (value < 0) return false;
            sum += value * weights[i];
        }

        int remainder = sum % 11;
        char expected = remainder == 10 ? 'X' : (char) ('0' + remainder);
        return checkChar == expected;
    }

    private int vinCharValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        return switch (c) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Y' -> 8;
            case 'R', 'Z' -> 9;
            default -> -1;
        };
    }

    private String normalizeDateLike(String text) {
        return normalizeNumericOnlyText(text).replace('.', '-').replace('년', '-').replace("월", "-");
    }

    /**
     * 숫자 문맥(날짜/번호/단위)에서만 사용하는 OCR 혼동 치환.
     * 알파벳 코드(예: 원동기형식 D4CB)에는 사용하면 안 된다.
     */
    private String normalizeNumericOnlyText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            sb.append(swapNumericLikeCharNumericOnly(c));
        }
        return sb.toString();
    }

    private char swapNumericLikeCharNumericOnly(char c) {
        return switch (c) {
            case 'O', 'Q', 'D' -> '0';
            case 'I', 'L' -> '1';
            case 'Z' -> '2';
            case 'S' -> '5';
            case 'G' -> '6';
            case 'B' -> '8';
            default -> c;
        };
    }

    private String normalizeDate(String yyyy, String mm, String dd) {
        if (yyyy == null || mm == null || dd == null) return null;
        return yyyy + "-" + pad2(mm) + "-" + pad2(dd);
    }

    private boolean isValidDate(String v) {
        if (v == null) return false;
        try {
            LocalDate date = LocalDate.parse(v);
            LocalDate min = LocalDate.of(1980, 1, 1);
            LocalDate max = LocalDate.now().plusDays(1);
            return !date.isBefore(min) && !date.isAfter(max);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean isValidDisplacement(String v) {
        if (v == null || !v.matches("\\d{3,5}")) return false;
        int cc = Integer.parseInt(v);
        return cc >= 500 && cc <= 10000;
    }

    private boolean isValidModelYear(Integer year) {
        if (year == null) return false;
        int min = 1980;
        int max = LocalDate.now().getYear() + 1;
        return year >= min && year <= max;
    }

    private boolean isValidManufactureYearMonth(String ym) {
        if (ym == null || ym.isBlank()) return false;
        if (!ym.matches("^\\d{4}-\\d{2}$")) return false;
        try {
            YearMonth value = YearMonth.parse(ym);
            YearMonth min = YearMonth.of(1980, 1);
            YearMonth max = YearMonth.now().plusMonths(1);
            return !value.isBefore(min) && !value.isAfter(max);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Integer inferModelYearFromVin(String vin) {
        if (!isValidVin(vin) || vin.length() < 10) return null;
        char code = vin.charAt(9);
        Integer base = switch (code) {
            case 'A' -> 1980;
            case 'B' -> 1981;
            case 'C' -> 1982;
            case 'D' -> 1983;
            case 'E' -> 1984;
            case 'F' -> 1985;
            case 'G' -> 1986;
            case 'H' -> 1987;
            case 'J' -> 1988;
            case 'K' -> 1989;
            case 'L' -> 1990;
            case 'M' -> 1991;
            case 'N' -> 1992;
            case 'P' -> 1993;
            case 'R' -> 1994;
            case 'S' -> 1995;
            case 'T' -> 1996;
            case 'V' -> 1997;
            case 'W' -> 1998;
            case 'X' -> 1999;
            case 'Y' -> 2000;
            case '1' -> 2001;
            case '2' -> 2002;
            case '3' -> 2003;
            case '4' -> 2004;
            case '5' -> 2005;
            case '6' -> 2006;
            case '7' -> 2007;
            case '8' -> 2008;
            case '9' -> 2009;
            default -> null;
        };
        if (base == null) return null;

        int maxAllowed = LocalDate.now().getYear() + 1;
        int inferred = base;
        while (inferred + 30 <= maxAllowed) {
            inferred += 30;
        }
        return inferred;
    }

    private Integer parseNullableInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private String cleanAddress(String address, String engineType) {
        if (address == null) return null;
        if (engineType != null && !engineType.isEmpty()) {
            address = address.replace(engineType, "").replaceAll("\\s{2,}", " ").trim();
            address = address.replaceAll(",\\s*$", "").trim();
        }
        return address.isEmpty() ? null : address;
    }

    private String stripFieldNum(String text) {
        return FIELD_NUM_PREFIX.matcher(text).replaceFirst("");
    }

    private String joinWords(List<OcrWord> words) {
        return words.stream().map(OcrWord::getText).collect(Collectors.joining(" ")).trim();
    }

    private String pad2(String n) {
        return n.length() == 1 ? "0" + n : n;
    }
}
