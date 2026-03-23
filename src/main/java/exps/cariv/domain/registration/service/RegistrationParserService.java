package exps.cariv.domain.registration.service;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.registration.dto.RegistrationParseResult;
import exps.cariv.domain.registration.dto.RegistrationParsed;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import exps.cariv.domain.upstage.service.DocumentTypeDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static exps.cariv.global.parser.ParserUtils.uniqueRows;

@Service
@Slf4j
public class RegistrationParserService {

    // =========================
    // 라벨(키워드)
    // =========================
    private static final List<String> PLATE = List.of("자동차등록번호");   // -> vehicleNo
    private static final List<String> CAR_TYPE = List.of("차종");         // -> carType
    private static final List<String> VEHICLE_USE = List.of("용도");      // -> vehicleUse
    private static final List<String> MODEL_NAME = List.of("차명");       // -> modelName
    private static final List<String> MODEL_CODE = List.of("형식", "제작연월", "형식및제작연월"); // -> modelCode
    private static final List<String> VIN = List.of("차대번호");          // -> vin
    private static final List<String> ENGINE = List.of("원동기형식");     // -> engineType
    private static final List<String> OWNER_NAME = List.of("소유자", "성명", "명칭"); // -> ownerName
    private static final List<String> OWNER_ID = List.of("생년월일", "법인등록번호"); // -> ownerId
    private static final List<String> ADDRESS = List.of("사용본거지");    // -> addressText

    // 제원 라벨
    private static final List<String> SPEC_LENGTH = List.of("길이");
    private static final List<String> SPEC_WIDTH  = List.of("너비");
    private static final List<String> SPEC_HEIGHT = List.of("높이");
    private static final List<String> SPEC_WEIGHT = List.of("총중량");
    private static final List<String> SPEC_SEATING = List.of("승차정원");
    private static final List<String> SPEC_DISP   = List.of("배기량", "축전지", "용량");
    private static final List<String> SPEC_MAXLOAD = List.of("최대적재량");
    private static final List<String> SPEC_POWER  = List.of("정격출력");
    private static final List<String> SPEC_FUEL   = List.of(
            "연료의종류",
            "연료의 종류",
            "연료종류",
            "연료의종류및연료소비율",
            "연료의 종류 및 연료소비율",
            "연료의 종류 및연료소비율"
    );

    // 최초등록일(있으면)
    private static final List<String> FIRST_REG_DATE = List.of("최초등록일");

    // =========================
    // 가드/정규식
    // =========================
    private static final List<String> WARNING_KEYWORDS = List.of("유의사항", "말소등록 사유");
    /** WARNING_KEYWORDS 사전 정규화 (isWarningRow 최적화) */
    private static final List<String> WARNING_KEYWORDS_NORM = WARNING_KEYWORDS.stream()
            .map(s -> s.replaceAll("\\s+","").toLowerCase()).toList();

    private static final List<String> LABEL_GUARDS = List.of(
            "자동차등록번호", "차종", "용도", "차명", "형식", "제작연월",
            "차대번호", "원동기형식", "사용본거지", "소유자", "성명", "명칭",
            "생년월일", "법인등록번호", "최초등록일"
    );
    /** LABEL_GUARDS 사전 정규화 (isSuspicious 최적화) */
    private static final List<String> LABEL_GUARDS_NORM = LABEL_GUARDS.stream()
            .map(s -> s.replaceAll("\\s+","").toLowerCase()).toList();

    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern PLATE_PATTERN = Pattern.compile("\\b\\d{2,3}[가-힣]\\d{4}\\b");
    private static final Pattern KOR_ID_PATTERN = Pattern.compile("\\b\\d{6}-\\d{7}\\b");
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d{1,6})");
    private static final Pattern ENGINE_TOKEN = Pattern.compile("\\b[A-Z0-9]{3,}\\b");
    private static final Pattern MODEL_CODE_TOKEN = Pattern.compile("\\b[A-Z0-9-]{5,}\\b");

    private static final Pattern DATE_PATTERN_1 = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\b");
    private static final Pattern DATE_PATTERN_2 = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일\\b");
    private static final Pattern DATE_PATTERN_3 = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\b");
    private static final Pattern DATE_PATTERN_4 = Pattern.compile("(19\\d{2}|20\\d{2})(0[1-9]|1[0-2])([0-2]\\d|3[01])");
    private static final Pattern YEAR_MONTH_PATTERN_1 = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\s*[./-]\\s*(\\d{1,2})\\b");
    private static final Pattern YEAR_MONTH_PATTERN_2 = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\s*년\\s*(\\d{1,2})\\s*월\\b");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    private static final List<String> MAIN_LABELS = List.of(
            "자동차등록번호","차종","용도","차명","형식","제작연월","차대번호","원동기형식","사용본거지","소유자","성명","명칭","법인등록번호","생년월일","최초등록일"
    );
    private static final List<String> SPEC_LABELS = List.of(
            "1.제원","제원관리번호","길이","너비","높이","총중량","승차정원","최대적재량","배기량","정격출력","연료"
    );

    private record TablePack(List<List<String>> rows, String plainText) {}

    // =========================
    // ✅ 외부 진입점: "등록증 맞는지" 검증 포함
    // =========================
    public RegistrationParseResult parseAndValidate(UpstageResponse res) {
        DocumentType detected = DocumentTypeDetector.detect(res);
        if (detected != DocumentType.REGISTRATION) {
            throw new IllegalArgumentException("등록증 업로드인데 문서 타입이 REGISTRATION이 아님: detected=" + detected);
        }
        return parse(res);
    }

    // =========================
    // 파서 본체 (예전 로직 그대로, 결과 키만 변경)
    // =========================
    public RegistrationParseResult parse(UpstageResponse json) {
        Map<String, String> result = new HashMap<>();

        List<UpstageElement> tableElements = json.elements().stream()
                .filter(e -> "table".equalsIgnoreCase(e.category()))
                .filter(e -> e.content() != null && e.content().html() != null && !e.content().html().isBlank())
                .toList();

        List<TablePack> packs = new ArrayList<>();
        for (UpstageElement te : tableElements) {
            String html = te.content().html();
            List<List<String>> rows = parseHtmlTableRobust(html);
            rows = uniqueRows(rows).stream()
                    .filter(r -> !isWarningRow(r))
                    .toList();

            String plain = safePlainText(html);
            packs.add(new TablePack(rows, plain));
        }

        TablePack mainPack = pickBestPack(packs, MAIN_LABELS);
        TablePack specPack = pickBestPack(packs, SPEC_LABELS);
        if (mainPack == null) mainPack = mergeAll(packs);
        if (specPack == null) specPack = mergeAll(packs);

        List<List<String>> mainRows = mainPack.rows();
        List<List<String>> specRows = specPack.rows();

        String mainText = rowsToText(mainRows) + " " + mainPack.plainText();
        String specText = rowsToText(specRows) + " " + specPack.plainText();
        String allText = (mainText + " " + specText).trim();

        // =========================
        // 메인 테이블 파싱
        // =========================
        for (List<String> row : mainRows) {
            if (row == null || row.isEmpty()) continue;

            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i);
                if (isBlank(cell)) continue;

                String next = pickValue(row, i);

                // vehicleNo (번호판)
                if (match(cell, PLATE)) {
                    String v = firstNonBlank(findPlate(next), extractInlineAfter(cell, "자동차등록번호"), findPlate(cell));
                    putIfAbsent(result, "vehicleNo", v);
                }

                // carType
                if (match(cell, CAR_TYPE)) {
                    putIfAbsent(result, "carType", cleanCompact(firstNonBlank(next, extractInlineAfter(cell, "차종"))));
                }

                // vehicleUse
                if (match(cell, VEHICLE_USE)) {
                    putIfAbsent(result, "vehicleUse", cleanCompact(firstNonBlank(next, extractInlineAfter(cell, "용도"))));
                }

                // modelName
                if (match(cell, MODEL_NAME)) {
                    putIfAbsent(result, "modelName", cleanCompact(firstNonBlank(next, extractInlineAfter(cell, "차명"))));
                }

                // modelCode
                if (match(cell, MODEL_CODE)) {
                    String v = firstNonBlank(next, extractInlineAfter(cell, "형식"));
                    v = firstNonBlank(findModelCode(v), findModelCode(cell), v);
                    putIfAbsent(result, "modelCode", cleanCompact(v));

                    Integer modelYear = extractYear(firstNonBlank(v, next, cell));
                    if (modelYear != null) {
                        putIfAbsent(result, "modelYear", String.valueOf(modelYear));
                    }

                    String manufactureYearMonth = extractYearMonth(firstNonBlank(v, next, cell));
                    putIfAbsent(result, "manufactureYearMonth", manufactureYearMonth);
                }

                // vin
                if (match(cell, VIN)) {
                    String v = firstNonBlank(next, extractInlineAfter(cell, "차대번호"));
                    v = firstNonBlank(extractVin(v), extractVin(cell), extractVin(mainText));
                    putIfAbsent(result, "vin", v);
                }

                // engineType
                if (match(cell, ENGINE)) {
                    String v = firstNonBlank(next, extractInlineAfter(cell, "원동기형식"));
                    v = firstNonBlank(findEngine(v), findEngine(cell), v);
                    putIfAbsent(result, "engineType", cleanCompact(v));

                    String manufactureYearMonth = extractYearMonth(firstNonBlank(next, cell));
                    putIfAbsent(result, "manufactureYearMonth", manufactureYearMonth);
                }

                // ownerName
                if (match(cell, OWNER_NAME)) {
                    String v = firstNonBlank(
                            ownerNameFromRow(row, i),
                            extractInlineAfter(cell, "소유자"),
                            extractInlineAfter(cell, "성명"),
                            extractInlineAfter(cell, "명칭"),
                            next
                    );
                    putIfAbsent(result, "ownerName", cleanCompact(v));
                }

                // ownerId
                if (match(cell, OWNER_ID)) {
                    String v = firstNonBlank(findKorId(next), findKorId(cell), next);
                    putIfAbsent(result, "ownerId", cleanCompact(v));
                }

                // addressText
                if (match(cell, ADDRESS)) {
                    putIfAbsent(result, "addressText", cleanCompact(firstNonBlank(next, extractInlineAfter(cell, "사용본거지"))));
                }

                // firstRegistratedAt (있으면)
                if (match(cell, FIRST_REG_DATE)) {
                    String v = firstNonBlank(next, extractInlineAfter(cell, "최초등록일"));
                    LocalDate d = parseDate(v);
                    if (d != null) {
                        putIfAbsent(result, "firstRegistratedAt", d.toString());
                        putIfAbsent(result, "modelYear", String.valueOf(d.getYear()));
                    }
                }
            }
        }

        // =========================
        // 제원 테이블 파싱
        // =========================
        for (List<String> row : specRows) {
            if (row == null || row.isEmpty()) continue;

            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i);
                if (isBlank(cell)) continue;

                String next = pickValue(row, i);

                if (match(cell, SPEC_LENGTH))  putIfAbsent(result, "lengthMm", firstNumber(next));
                if (match(cell, SPEC_WIDTH))   putIfAbsent(result, "widthMm", firstNumber(next));
                if (match(cell, SPEC_HEIGHT))  putIfAbsent(result, "heightMm", firstNumber(next));
                if (match(cell, SPEC_WEIGHT))  putIfAbsent(result, "weightKg", firstNumber(next));
                if (match(cell, SPEC_SEATING)) putIfAbsent(result, "seating", firstNumber(next));
                if (match(cell, SPEC_DISP))    putIfAbsent(result, "displacement", firstNumber(next));
                if (match(cell, SPEC_MAXLOAD)) putIfAbsent(result, "maxLoadKg", firstNumber(next));
                if (match(cell, SPEC_POWER))   putIfAbsent(result, "powerKw", firstNumber(next));
                if (match(cell, SPEC_FUEL))    putIfAbsent(result, "fuelType", extractFuelType(next));
            }
        }

        // =========================
        // 보험(정규식 + 오염 제거)
        // =========================
        if (isBlank(result.get("vin"))) result.put("vin", extractVin(allText));
        else result.put("vin", extractVin(result.get("vin")));

        result.put("vehicleNo", sanitizePlate(result.get("vehicleNo"), allText));
        if (isBlank(result.get("ownerId"))) result.put("ownerId", findKorId(allText));

        result.put("carType", sanitizeLabelValue(result.get("carType"), allText, "차종",
                List.of("용도","차명","형식","제작연월","차대번호","원동기형식","사용본거지","소유자")));

        result.put("vehicleUse", sanitizeLabelValue(result.get("vehicleUse"), allText, "용도",
                List.of("차명","형식","제작연월","차대번호","원동기형식","사용본거지","소유자")));

        result.put("modelName", sanitizeLabelValue(result.get("modelName"), allText, "차명",
                List.of("형식","제작연월","차대번호","원동기형식","사용본거지","소유자")));

        result.put("addressText", sanitizeLabelValue(result.get("addressText"), allText, "사용본거지",
                List.of("소유자","성명","명칭","생년월일","법인등록번호")));

        result.put("ownerName", sanitizeOwnerName(result.get("ownerName"), allText));
        result.put("modelCode", sanitizeModelCode(result.get("modelCode"), allText));
        result.put("engineType", sanitizeEngineType(result.get("engineType"), allText));
        result.put("fuelType", sanitizeFuelType(result.get("fuelType"), allText));

        if (isBlank(result.get("modelYear"))) {
            Integer year = firstNonNull(
                    extractYear(result.get("modelCode")),
                    extractYear(result.get("engineType")),
                    extractYear(result.get("firstRegistratedAt")),
                    extractYear(allText)
            );
            if (year != null) {
                result.put("modelYear", String.valueOf(year));
            }
        }

        // =========================
        // missing / errorFields
        // =========================
        List<String> requiredKeys = List.of(
                "vin","vehicleNo","carType","vehicleUse","modelName","engineType","ownerName","ownerId"
        );

        List<String> missing = requiredKeys.stream()
                .filter(k -> isBlank(result.get(k)))
                .toList();

        List<String> errorFields = new ArrayList<>();
        if (!isBlank(result.get("vehicleNo")) && !PLATE_PATTERN.matcher(result.get("vehicleNo").replaceAll("\\s+","")).matches()) {
            errorFields.add("vehicleNo");
        }
        if (!isBlank(result.get("vin")) && !VIN_PATTERN.matcher(result.get("vin").replaceAll("\\s+","").toUpperCase()).matches()) {
            errorFields.add("vin");
        }
        if (!isBlank(result.get("ownerId")) && !KOR_ID_PATTERN.matcher(result.get("ownerId")).matches()) {
            errorFields.add("ownerId");
        }

        RegistrationParsed parsed = new RegistrationParsed(
                cleanCompact(result.get("vin")),
                cleanCompact(result.get("vehicleNo")),
                cleanCompact(result.get("carType")),
                cleanCompact(result.get("vehicleUse")),
                cleanCompact(result.get("modelName")),
                cleanCompact(result.get("engineType")),
                null, // mileageKm: 등록증에 보통 없음
                cleanCompact(result.get("ownerName")),
                cleanCompact(result.get("ownerId")),
                toInt(result.get("modelYear")),
                cleanCompact(result.get("fuelType")),
                cleanCompact(result.get("manufactureYearMonth")),
                toInt(result.get("displacement")),
                parseDate(result.get("firstRegistratedAt")),
                cleanCompact(result.get("modelCode")),
                cleanCompact(result.get("addressText")),
                toInt(result.get("lengthMm")),
                toInt(result.get("widthMm")),
                toInt(result.get("heightMm")),
                toInt(result.get("weightKg")),
                toInt(result.get("seating")),
                toInt(result.get("maxLoadKg")),
                toInt(result.get("powerKw"))
        );

        log.info("[Registration] Parsed: {}", parsed);
        if (!missing.isEmpty()) log.warn("[Registration] Missing fields: {}", missing);

        return new RegistrationParseResult(parsed, missing, errorFields);
    }

    // =========================
    // 아래는 네 예전 헬퍼들 유지(필요한 것만)
    // =========================

    private int toIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private Integer toInt(String s) {
        if (isBlank(s)) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private LocalDate parseDate(String s) {
        if (isBlank(s)) return null;
        String t = s.trim();
        Matcher m = DATE_PATTERN_1.matcher(t);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            try {
                return LocalDate.of(y, mo, d);
            } catch (Exception ignore) {}
        }

        m = DATE_PATTERN_2.matcher(t);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            try {
                return LocalDate.of(y, mo, d);
            } catch (Exception ignore) {}
        }

        m = DATE_PATTERN_3.matcher(t);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            try {
                return LocalDate.of(y, mo, d);
            } catch (Exception ignore) {}
        }

        String digits = t.replaceAll("[^0-9]", "");
        m = DATE_PATTERN_4.matcher(digits);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            try {
                return LocalDate.of(y, mo, d);
            } catch (Exception ignore) {}
        }
        // "2026-02-07" 같이 이미 ISO면
        try { return LocalDate.parse(t); } catch (Exception ignore) {}
        return null;
    }

    private String extractYearMonth(String source) {
        if (isBlank(source)) return null;
        Matcher m = YEAR_MONTH_PATTERN_1.matcher(source);
        if (m.find()) {
            return formatYearMonth(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        m = YEAR_MONTH_PATTERN_2.matcher(source);
        if (m.find()) {
            return formatYearMonth(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return null;
    }

    private Integer extractYear(String source) {
        if (isBlank(source)) return null;
        Matcher m = YEAR_PATTERN.matcher(source);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractYearMonthNearKeyword(String text, String keyword) {
        if (isBlank(text) || isBlank(keyword)) return null;
        int idx = text.indexOf(keyword);
        if (idx < 0) return null;
        int from = Math.max(0, idx - 24);
        int to = Math.min(text.length(), idx + 120);
        return extractYearMonth(text.substring(from, to));
    }

    private String formatYearMonth(int year, int month) {
        if (month < 1 || month > 12) return null;
        return String.format("%04d-%02d", year, month);
    }

    private String extractFuelType(String source) {
        if (isBlank(source)) return null;
        String normalized = source.replaceAll("\\s+", " ").trim();
        String[] candidates = {
                "휘발유(무연)", "휘발유", "경유", "디젤", "LPG", "LNG", "CNG",
                "전기", "수소", "하이브리드"
        };
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) {
                return candidate;
            }
        }
        String cleaned = normalized
                .replaceAll("(?i)km/L|km/kWh|km/kg|ps/rpm|cc|kg|mm|ah|kW/rpm", " ")
                .replaceAll("[0-9.,/()\\[\\]✓☑]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) return null;
        if (cleaned.length() > 20) return null;
        return cleaned;
    }

    private String pickValue(List<String> row, int labelIdx) {
        for (int j = labelIdx + 1; j < Math.min(row.size(), labelIdx + 4); j++) {
            String v = row.get(j);
            if (isBlank(v)) continue;
            if (looksLikeTitle(v)) continue;
            if (isSuspicious(v)) continue;
            return cleanCompact(v);
        }
        return null;
    }

    private boolean looksLikeTitle(String v) {
        String n = normalize(v);
        return n.contains("자동차등록증") || n.contains("발급") || n.contains("증명합니다");
    }

    private TablePack pickBestPack(List<TablePack> packs, List<String> labels) {
        TablePack best = null;
        int bestScore = 0;
        for (TablePack p : packs) {
            int score = scorePack(p, labels);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return bestScore == 0 ? null : best;
    }

    private int scorePack(TablePack p, List<String> labels) {
        if (p == null) return 0;
        // labels를 루프 밖에서 한 번만 정규화
        List<String> normalizedLabels = labels.stream().map(this::normalize).toList();
        Set<String> hit = new HashSet<>();
        for (List<String> row : p.rows()) {
            for (String cell : row) {
                String n = normalize(cell);
                for (String lbn : normalizedLabels) {
                    if (n.contains(lbn)) hit.add(lbn);
                }
            }
        }
        String pn = normalize(p.plainText());
        for (String lbn : normalizedLabels) {
            if (pn.contains(lbn)) hit.add(lbn);
        }
        return hit.size();
    }

    private TablePack mergeAll(List<TablePack> packs) {
        List<List<String>> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (TablePack p : packs) {
            rows.addAll(p.rows());
            if (p.plainText() != null) sb.append(' ').append(p.plainText());
        }
        return new TablePack(uniqueRows(rows), sb.toString());
    }

    private String rowsToText(List<List<String>> rows) {
        return rows.stream().flatMap(List::stream).filter(Objects::nonNull).collect(Collectors.joining(" "));
    }

private String safePlainText(String html) {
    if (html == null) return "";
    // 태그 제거 + 기본 엔티티 디코딩(필요하면 확장)
    return html.replaceAll("(?is)<[^>]*>", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .trim()
            .replaceAll("\\s+", " ");
}



private List<List<String>> parseHtmlTableRobust(String html) {
    List<List<String>> rows = new ArrayList<>();
    if (html == null || html.isBlank()) return rows;

    String src = html.replace("\n", " ").replace("\r", " ");

    java.util.regex.Matcher tr = java.util.regex.Pattern
            .compile("(?is)<tr[^>]*>(.*?)</tr>")
            .matcher(src);

    while (tr.find()) {
        String trHtml = tr.group(1);
        java.util.regex.Matcher cell = java.util.regex.Pattern
                .compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>")
                .matcher(trHtml);

        List<String> row = new ArrayList<>();
        while (cell.find()) {
            row.add(safePlainText(cell.group(1)));
        }

        boolean any = row.stream().anyMatch(s -> s != null && !s.isBlank());
        if (any) rows.add(row);
    }
    return rows;
}





    private void putIfAbsent(Map<String, String> m, String k, String v) {
        if (isBlank(m.get(k)) && !isBlank(v)) m.put(k, v.trim());
    }

    private String extractInlineAfter(String cell, String keyword) {
        if (cell == null) return null;
        String c = cell.replaceAll("\\s+","");
        String k = keyword.replaceAll("\\s+","");
        int idx = c.indexOf(k);
        if (idx < 0) return null;
        String after = c.substring(idx + k.length());
        return after.isBlank() ? null : after;
    }

    private String extractVin(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+","").toUpperCase();
        Matcher m = VIN_PATTERN.matcher(t);
        return m.find() ? m.group() : null;
    }

    private String findPlate(String s) {
        if (s == null) return null;
        Matcher m = PLATE_PATTERN.matcher(s.replaceAll("\\s+",""));
        return m.find() ? m.group() : null;
    }

    private String findKorId(String s) {
        if (s == null) return null;
        Matcher m = KOR_ID_PATTERN.matcher(s);
        return m.find() ? m.group() : null;
    }

    private String findEngine(String s) {
        if (s == null) return null;
        Matcher m = ENGINE_TOKEN.matcher(s.toUpperCase());
        return m.find() ? m.group() : null;
    }

    private String findModelCode(String s) {
        if (s == null) return null;
        Matcher m = MODEL_CODE_TOKEN.matcher(s.toUpperCase());
        return m.find() ? m.group() : null;
    }

    private String firstNumber(String s) {
        if (s == null) return null;
        Matcher m = FIRST_NUMBER.matcher(s.replaceAll(",",""));
        return m.find() ? m.group(1) : null;
    }

    private String cleanCompact(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (!isBlank(v)) return v;
        return null;
    }

    private boolean match(String text, List<String> labels) {
        String n = normalize(text);
        return labels.stream().anyMatch(l -> n.contains(normalize(l)));
    }

    private boolean isWarningRow(List<String> row) {
        if (row == null || row.isEmpty()) return false;
        return row.stream().filter(Objects::nonNull).map(this::normalize)
                .anyMatch(cell -> WARNING_KEYWORDS_NORM.stream().anyMatch(cell::contains));
    }

    private String sanitizePlate(String current, String allText) {
        String value = cleanCompact(current);
        if (value != null) {
            String plate = findPlate(value);
            if (!isBlank(plate)) return plate;
        }
        return findPlate(allText);
    }

    private String sanitizeLabelValue(String current, String allText, String label, List<String> stopLabels) {
        String value = cleanCompact(current);
        if (!isSuspicious(value)) return value;

        String extracted = extractInlineAfter(allText, label);
        if (isSuspicious(extracted)) return null;
        // stopLabels 정교화가 필요하면 너 예전 버전처럼 stopIndex 적용하면 됨
        return cleanCompact(extracted);
    }

    private String sanitizeOwnerName(String current, String allText) {
        String value = cleanCompact(current);
        if (isSuspicious(value) || isOwnerLabel(value)) {
            String extracted = extractInlineAfter(allText, "성명");
            if (isBlank(extracted)) extracted = extractInlineAfter(allText, "명칭");
            return cleanCompact(stripLeadingLabel(extracted));
        }
        return value;
    }

    private String sanitizeModelCode(String current, String allText) {
        String value = cleanCompact(current);
        if (value != null && value.matches(".*[A-Za-z].*") && findModelCode(value) != null) return value;
        String extracted = extractInlineAfter(allText, "형식");
        return cleanCompact(firstNonBlank(findModelCode(extracted), extracted));
    }

    private String sanitizeEngineType(String current, String allText) {
        String value = cleanCompact(current);
        if (value != null && value.matches(".*[A-Za-z].*") && findEngine(value) != null) return value;
        String extracted = extractInlineAfter(allText, "원동기형식");
        return cleanCompact(firstNonBlank(findEngine(extracted), extracted));
    }

    private String sanitizeFuelType(String current, String allText) {
        String value = extractFuelType(current);
        if (!isBlank(value) && !isFuelNoise(value)) return value;

        String extracted = firstNonBlank(
                extractInlineAfter(allText, "연료의종류및연료소비율"),
                extractInlineAfter(allText, "연료의 종류 및 연료소비율"),
                extractInlineAfter(allText, "연료의 종류 및연료소비율"),
                extractInlineAfter(allText, "연료의종류"),
                extractInlineAfter(allText, "연료의 종류"),
                extractInlineAfter(allText, "연료종류")
        );
        String extractedFuel = extractFuelType(extracted);
        if (!isBlank(extractedFuel) && !isFuelNoise(extractedFuel)) return extractedFuel;
        return null;
    }

    private boolean isFuelNoise(String value) {
        if (isBlank(value)) return true;
        String n = normalize(value);
        return n.contains("기통")
                || n.contains("kw")
                || n.contains("rpm")
                || n.contains("ps")
                || n.contains("ah")
                || n.contains("kg")
                || n.contains("mm")
                || n.contains("최고출력")
                || n.contains("정격출력");
    }

    private String ownerNameFromRow(List<String> row, int index) {
        String v = pickValue(row, index);
        if (!isBlank(v) && !isOwnerLabel(v)) return v;

        for (int i = 0; i < row.size(); i++) {
            String c = row.get(i);
            if (isBlank(c)) continue;
            String n = normalize(c);
            if (n.contains("성명") || n.contains("명칭")) {
                String nv = pickValue(row, i);
                if (!isBlank(nv)) return nv;
            }
        }
        return null;
    }

    private boolean isSuspicious(String value) {
        if (isBlank(value)) return true;
        if (value.length() > 80) return true;
        String normalized = normalize(value);
        return LABEL_GUARDS_NORM.stream().anyMatch(normalized::contains) || looksLikeTitle(value);
    }

    private boolean isOwnerLabel(String value) {
        if (value == null) return false;
        String normalized = normalize(value);
        return normalized.equals("소유자") || normalized.equals("성명") || normalized.equals("명칭");
    }

    private String stripLeadingLabel(String value) {
        if (value == null) return null;
        return value.replaceFirst("^\\s*\\(?(성명|명칭)\\)?\\s*", "");
    }

    private String normalize(String s) {
        return (s == null) ? "" : s.replaceAll("\\s+","").toLowerCase();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

}
