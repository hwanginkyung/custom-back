package exps.cariv.domain.malso.service;

import exps.cariv.domain.malso.dto.DeregParseResult;
import exps.cariv.domain.malso.dto.ParsedDereg;
import exps.cariv.domain.upstage.dto.Content;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static exps.cariv.global.parser.ParserUtils.extractVin;
import static exps.cariv.global.parser.ParserUtils.firstNonBlank;
import static exps.cariv.global.parser.ParserUtils.isBlank;
import static exps.cariv.global.parser.ParserUtils.isMissing;
import static exps.cariv.global.parser.ParserUtils.nextNonBlank;
import static exps.cariv.global.parser.ParserUtils.normalize;
import static exps.cariv.global.parser.ParserUtils.normalizeLabel;
import static exps.cariv.global.parser.ParserUtils.parseHtmlTable;
import static exps.cariv.global.parser.ParserUtils.putIfAbsent;
import static exps.cariv.global.parser.ParserUtils.uniqueRows;

/**
 * 말소증 OCR 파싱 서비스.
 */
@Service
@Slf4j
public class DeregistrationParserService {

    // === 라벨 매칭 ===
    private static final List<String> DOC_NO_LABELS = List.of("증명번호", "증 명 번 호", "문서확인번호", "문 서 확 인 번 호");
    private static final List<String> SPEC_NO_LABELS = List.of("규격번호", "규 격 번 호", "제원관리번호", "제 원 관 리 번 호");
    private static final List<String> REG_NO_LABELS = List.of("등록번호", "등 록 번 호", "자동차등록번호");
    private static final List<String> DEREG_DATE_LABELS = List.of("말소등록일", "말소등록 일", "말소일자", "말소 일자");
    private static final List<String> OWNER_BIRTH_LABELS = List.of("생년월일", "Date of Birth", "DateofBirth");
    private static final List<String> DEREG_REASON_LABELS = List.of(
            "말소사유", "말소 사유", "말소등록사유", "말소등록 구분", "말소등록구분", "말 소 등 록 구 분", "Reason"
    );
    private static final List<String> RIGHTS_LABELS = List.of("권리관계", "권 리 관 계", "저당", "압류");
    private static final List<String> VIN_LABELS = List.of("차대번호", "차 대 번 호");
    private static final List<String> MODEL_LABELS = List.of("차명", "차 명");
    private static final List<String> OWNER_LABELS = List.of("소유자", "성명", "명칭");

    private static final List<String> REQUIRED_KEYS = List.of("registrationNo", "deRegistrationDate");

    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern PLATE_PATTERN = Pattern.compile("\\b\\d{2,3}[가-힣]\\d{4}\\b");
    private static final Pattern PLATE_FALLBACK_PATTERN = Pattern.compile("(?<!\\d)\\d{2,3}[가-힣]\\d{4}(?!\\d)");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[.\\-/년\\s]+(\\d{1,2})[.\\-/월\\s]+(\\d{1,2})");
    private static final Pattern OWNER_ID_PATTERN = Pattern.compile("(?<!\\d)\\d{6}-?\\d{7}(?!\\d)");
    private static final Pattern BUSINESS_NO_PATTERN = Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{5}(?!\\d)");
    private static final Pattern DOC_NO_PATTERN = Pattern.compile("(?<!\\d)\\d{4}-\\d{6}-\\d{6}(?!\\d)");
    private static final Pattern SPEC_NO_PATTERN = Pattern.compile("(?<![A-Z0-9])[A-Z]\\d{2}-\\d-\\d{5}-\\d{4}-\\d{4}(?![A-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEREG_REASON_VALUE_PATTERN = Pattern.compile(
            "(수출예정\\s*\\(\\s*수출말소\\s*\\)|폐차\\s*\\(\\s*자진말소\\s*\\)|수출예정|수출말소|자진말소|폐차)"
    );
    private static final Pattern MODEL_YEAR_WITH_LABEL_PATTERN = Pattern.compile(
            "(연식|모델년도|모델연도|년식|model\\s*year|year)\\D{0,15}((19|20)\\d{2})",
            Pattern.CASE_INSENSITIVE
    );

    public DeregParseResult parseAndValidate(UpstageResponse res) {
        Map<String, String> map = new HashMap<>();

        List<UpstageElement> elements = Optional.ofNullable(res)
                .map(UpstageResponse::elements)
                .orElse(Collections.emptyList());

        List<UpstageElement> tableElements = elements.stream()
                .filter(e -> "table".equalsIgnoreCase(e.category()))
                .filter(e -> e.content() != null && e.content().html() != null)
                .toList();

        List<List<String>> allRows = new ArrayList<>();
        for (UpstageElement table : tableElements) {
            allRows.addAll(parseHtmlTable(table.content().html()));
        }
        allRows = uniqueRows(allRows);

        String rowText = allRows.stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        String contentText = elements.stream()
                .map(UpstageElement::content)
                .map(this::contentToText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        String allText = Stream.of(rowText, contentText)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(" "));

        // 행별 라벨-값 매칭
        for (List<String> row : allRows) {
            if (row == null || row.isEmpty()) continue;
            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i);
                if (isBlank(cell)) continue;
                String next = nextNonBlank(row, i + 1);

                if (matchLabel(cell, DOC_NO_LABELS))
                    putIfAbsent(map, "documentNo", normalize(next));
                if (matchLabel(cell, SPEC_NO_LABELS))
                    putIfAbsent(map, "specNo", normalize(next));
                if (matchLabel(cell, REG_NO_LABELS)) {
                    putIfAbsent(map, "registrationNo",
                            firstNonBlank(findPlateValue(next), findPlateValue(cell), normalize(next)));
                }
                if (matchLabel(cell, DEREG_DATE_LABELS))
                    putIfAbsent(map, "deRegistrationDate", normalize(next));
                if (matchLabel(cell, DEREG_REASON_LABELS))
                    putIfAbsent(map, "deRegistrationReason", normalize(next));
                if (matchLabel(cell, RIGHTS_LABELS))
                    putIfAbsent(map, "rightsRelation", normalize(next));
                if (matchLabel(cell, VIN_LABELS)) {
                    String raw = firstNonBlank(normalize(next), normalize(cell));
                    putIfAbsent(map, "vin", firstNonBlank(extractVin(raw), extractVin(cell)));
                }
                if (matchLabel(cell, MODEL_LABELS))
                    putIfAbsent(map, "modelName", normalize(next));
                if (matchLabel(cell, OWNER_LABELS))
                    putIfAbsent(map, "ownerName", sanitizeOwnerName(next));
            }
        }

        // 전체 텍스트에서 보완
        if (isBlank(map.get("registrationNo"))) map.put("registrationNo", findPlateValue(allText));
        if (isBlank(map.get("vin"))) {
            String vin = extractVin(allText);
            if (!isBlank(vin)) map.put("vin", vin);
        }
        if (isBlank(map.get("ownerId"))) {
            map.put("ownerId", findOwnerId(allText));
        }
        if (isBlank(map.get("modelYear"))) {
            Integer modelYear = findModelYear(allText);
            if (modelYear != null) map.put("modelYear", String.valueOf(modelYear));
        }
        if (isBlank(map.get("deRegistrationReason"))) {
            String reason = findDeregReasonValue(allText);
            if (!isBlank(reason)) map.put("deRegistrationReason", reason);
        }
        if (isBlank(map.get("documentNo"))) {
            String documentNo = findDocumentNo(allText);
            if (!isBlank(documentNo)) map.put("documentNo", documentNo);
        }
        if (isBlank(map.get("specNo"))) {
            String specNo = findSpecNo(allText);
            if (!isBlank(specNo)) map.put("specNo", specNo);
        }
        if (isBlank(map.get("deRegistrationDate"))) {
            String dateByKeyword = findDateByKeyword(allText, "말소등록일", "말소일자", "Date of De-registration");
            if (!isBlank(dateByKeyword)) map.put("deRegistrationDate", dateByKeyword);
        }

        // 날짜 파싱
        LocalDate deregDate = parseDate(map.get("deRegistrationDate"));
        Integer modelYear = parseInt(map.get("modelYear"));

        ParsedDereg parsed = new ParsedDereg(
                map.get("vin"),
                map.get("registrationNo"),
                map.get("documentNo"),
                map.get("specNo"),
                map.get("registrationNo"),
                deregDate,
                map.get("deRegistrationReason"),
                map.get("rightsRelation"),
                map.get("modelName"),
                modelYear,
                sanitizeOwnerName(map.get("ownerName")),
                map.get("ownerId"),
                null   // mileageKm
        );

        List<String> missing = REQUIRED_KEYS.stream().filter(k -> isMissing(map.get(k))).toList();

        List<String> errorFields = new ArrayList<>();
        if (!isBlank(map.get("registrationNo")) && !PLATE_PATTERN.matcher(map.get("registrationNo").replaceAll("\\s+", "")).matches())
            errorFields.add("registrationNo");

        log.info("[Deregistration] Parsed: {}", parsed);
        if (!missing.isEmpty()) log.warn("[Deregistration] Missing: {}", missing);

        return new DeregParseResult(parsed, missing, errorFields);
    }

    // ─── 유틸리티 ───

    private LocalDate parseDate(String s) {
        if (isBlank(s)) return null;
        try {
            Matcher m = DATE_PATTERN.matcher(s);
            if (m.find()) {
                return LocalDate.of(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))
                );
            }
        } catch (Exception e) {
            log.debug("[Deregistration] date parse failed: {}", s);
        }
        return null;
    }

    private String findOwnerId(String s) {
        if (s == null) return null;
        String compact = s.replaceAll("\\s+", "");

        Matcher owner = OWNER_ID_PATTERN.matcher(compact);
        if (owner.find()) return owner.group();

        Matcher biz = BUSINESS_NO_PATTERN.matcher(compact);
        if (biz.find()) return biz.group();

        String ownerBirthDate = findDateByKeyword(
                s,
                OWNER_BIRTH_LABELS.toArray(String[]::new)
        );
        if (!isBlank(ownerBirthDate)) {
            return ownerBirthDate;
        }

        return null;
    }

    private Integer findModelYear(String s) {
        if (isBlank(s)) return null;

        Matcher withLabel = MODEL_YEAR_WITH_LABEL_PATTERN.matcher(s);
        if (withLabel.find()) {
            return parseInt(withLabel.group(2));
        }

        return null;
    }

    private String findDocumentNo(String s) {
        if (isBlank(s)) return null;
        Matcher m = DOC_NO_PATTERN.matcher(s.replaceAll("\\s+", ""));
        return m.find() ? m.group() : null;
    }

    private String findSpecNo(String s) {
        if (isBlank(s)) return null;
        Matcher m = SPEC_NO_PATTERN.matcher(s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        return m.find() ? m.group() : null;
    }

    private String findDeregReasonValue(String s) {
        if (isBlank(s)) return null;
        Matcher m = DEREG_REASON_VALUE_PATTERN.matcher(s.replaceAll("\\s+", ""));
        if (!m.find()) return null;
        String compact = m.group(1).replaceAll("\\s+", "");
        return switch (compact) {
            case "수출예정(수출말소)", "수출예정", "수출말소" -> "수출예정(수출말소)";
            case "폐차(자진말소)", "폐차", "자진말소" -> "폐차(자진말소)";
            default -> m.group(1);
        };
    }

    private String findDateByKeyword(String s, String... keywords) {
        if (isBlank(s)) return null;
        String compact = s.replaceAll("\\s+", "");
        for (String keyword : keywords) {
            String k = keyword.replaceAll("\\s+", "");
            int idx = compact.indexOf(k);
            if (idx < 0) continue;
            int end = Math.min(compact.length(), idx + 60);
            String window = compact.substring(idx, end);
            Matcher m = DATE_PATTERN.matcher(window);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    private String findPlateValue(String s) {
        if (isBlank(s)) return null;
        Matcher m = PLATE_FALLBACK_PATTERN.matcher(s.replaceAll("\\s+", ""));
        return m.find() ? m.group() : null;
    }

    private String sanitizeOwnerName(String value) {
        if (isBlank(value)) return null;
        String trimmed = value.trim();
        String normalized = trimmed.replaceAll("\\s+", "").toLowerCase();

        // OCR이 라벨 셀을 값으로 잘못 읽는 대표 케이스 방지 (예: "성명(명칭)Name")
        boolean looksLikeLabel = normalized.contains("성명")
                || normalized.contains("명칭")
                || normalized.contains("name");
        if (looksLikeLabel) {
            return null;
        }
        return trimmed;
    }

    private Integer parseInt(String value) {
        if (isBlank(value)) return null;
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String contentToText(Content content) {
        if (content == null) return null;
        return firstNonBlank(content.text(), content.markdown(), stripHtml(content.html()));
    }

    private String stripHtml(String html) {
        if (isBlank(html)) return null;
        return html.replaceAll("<[^>]*>", " ");
    }

    private boolean matchLabel(String text, List<String> labels) {
        if (text == null) return false;
        String n = normalizeLabel(text);
        if (n.isEmpty() || n.length() > 15) return false;
        for (String label : labels) {
            String ln = normalizeLabel(label);
            if (n.equals(ln)) return true;
            if (n.startsWith(ln) && n.length() <= ln.length() + 2) return true;
            if (n.contains(ln)) return true;
        }
        return false;
    }
}
