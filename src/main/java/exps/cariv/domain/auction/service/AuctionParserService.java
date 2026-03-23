package exps.cariv.domain.auction.service;

import exps.cariv.domain.auction.dto.AuctionParseResult;
import exps.cariv.domain.auction.dto.AuctionParsed;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static exps.cariv.global.parser.ParserUtils.extractInlineAfter;
import static exps.cariv.global.parser.ParserUtils.extractVin;
import static exps.cariv.global.parser.ParserUtils.findPlate;
import static exps.cariv.global.parser.ParserUtils.firstNonBlank;
import static exps.cariv.global.parser.ParserUtils.isBlank;
import static exps.cariv.global.parser.ParserUtils.nextNonBlank;
import static exps.cariv.global.parser.ParserUtils.parseHtmlTable;
import static exps.cariv.global.parser.ParserUtils.putIfAbsent;
import static exps.cariv.global.parser.ParserUtils.uniqueRows;

/**
 * 경락사실확인서 OCR 파싱 서비스.
 * 참조: 1차본 AuctionParserService 기반, 현재 코드 패턴에 맞게 조정.
 */
@Service
@Slf4j
public class AuctionParserService {

    private static final List<String> REG_NO_LABELS = List.of("차량번호", "자동차등록번호", "등록번호");
    private static final List<String> VIN_LABELS = List.of("차대번호", "차대 번호");
    private static final List<String> MODEL_LABELS = List.of("차명", "모델명");
    private static final List<String> YEAR_LABELS = List.of("년식", "연식", "모델연도", "연도");
    private static final List<String> MILEAGE_LABELS = List.of("주행거리", "주행 거리", "주행(km)", "주행 km");
    private static final List<String> DISPLACEMENT_LABELS = List.of("배기량");
    private static final List<String> INITIAL_REG_LABELS = List.of("최초등록일", "최초 등록일", "최초등록", "등록일", "등록일자");
    private static final List<String> FUEL_LABELS = List.of("연료", "연료종류", "연료 종류");
    private static final List<String> COLOR_LABELS = List.of("색상", "차량색상", "색");

    private static final List<String> REQUIRED_KEYS = List.of(
            "registrationNo", "chassisNo", "model", "modelYear",
            "mileage", "displacement", "initialRegistrationDate", "fuel", "color"
    );

    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern PLATE_PATTERN = Pattern.compile("\\b\\d{2,3}[가-힣]\\d{4}\\b");
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d{1,9})");

    public AuctionParseResult parseAndValidate(UpstageResponse res) {
        Map<String, String> map = new HashMap<>();

        List<UpstageElement> tableElements = res.elements().stream()
                .filter(e -> "table".equalsIgnoreCase(e.category()))
                .filter(e -> e.content() != null && e.content().html() != null)
                .toList();

        List<List<String>> allRows = new ArrayList<>();
        for (UpstageElement table : tableElements) {
            allRows.addAll(parseHtmlTable(table.content().html()));
        }
        allRows = uniqueRows(allRows);

        String allText = allRows.stream().flatMap(List::stream)
                .filter(Objects::nonNull).collect(Collectors.joining(" "));

        for (List<String> row : allRows) {
            if (row == null || row.isEmpty()) continue;
            for (int i = 0; i < row.size(); i++) {
                String cell = row.get(i);
                if (isBlank(cell)) continue;
                String next = nextNonBlank(row, i + 1);

                if (match(cell, REG_NO_LABELS)) {
                    putIfAbsent(map, "registrationNo",
                            firstNonBlank(next, extractInlineAfter(cell, "차량번호"), findPlate(cell), findPlate(next)));
                }
                if (match(cell, VIN_LABELS)) {
                    String v = firstNonBlank(next, extractInlineAfter(cell, "차대번호"));
                    putIfAbsent(map, "chassisNo", firstNonBlank(extractVin(v), extractVin(cell)));
                }
                if (match(cell, MODEL_LABELS)) {
                    putIfAbsent(map, "model", clean(firstNonBlank(next, extractInlineAfter(cell, "차명"))));
                }
                if (match(cell, YEAR_LABELS)) {
                    putIfAbsent(map, "modelYear", firstNumber(firstNonBlank(next, extractInlineAfter(cell, "년식"))));
                }
                if (match(cell, MILEAGE_LABELS)) {
                    putIfAbsent(map, "mileage", firstNumber(firstNonBlank(next, extractInlineAfter(cell, "주행거리"))));
                }
                if (match(cell, DISPLACEMENT_LABELS)) {
                    putIfAbsent(map, "displacement", firstNumber(firstNonBlank(next, extractInlineAfter(cell, "배기량"))));
                }
                if (match(cell, INITIAL_REG_LABELS)) {
                    putIfAbsent(map, "initialRegistrationDate", clean(firstNonBlank(next, extractInlineAfter(cell, "최초등록일"))));
                }
                if (match(cell, FUEL_LABELS)) {
                    putIfAbsent(map, "fuel", clean(firstNonBlank(next, extractInlineAfter(cell, "연료"))));
                }
                if (match(cell, COLOR_LABELS)) {
                    putIfAbsent(map, "color", clean(firstNonBlank(next, extractInlineAfter(cell, "색상"))));
                }
            }
        }

        // fallback: 전체 텍스트에서 재탐색
        if (isBlank(map.get("registrationNo"))) map.put("registrationNo", findPlate(allText));
        if (isBlank(map.get("chassisNo"))) map.put("chassisNo", extractVin(allText));

        AuctionParsed parsed = new AuctionParsed(
                map.get("registrationNo"),
                map.get("chassisNo"),
                map.get("model"),
                parseYear(map.get("modelYear")),
                parseLong(map.get("mileage")),
                parseInteger(map.get("displacement")),
                parseDateFlexible(map.get("initialRegistrationDate")),
                map.get("fuel"),
                map.get("color")
        );

        List<String> missing = REQUIRED_KEYS.stream().filter(k -> isBlank(map.get(k))).toList();

        List<String> errorFields = new ArrayList<>();
        validateField(map, "registrationNo", PLATE_PATTERN, errorFields);
        validateVin(map, errorFields);
        if (!isBlank(map.get("modelYear")) && parsed.modelYear() == null) errorFields.add("modelYear");
        if (!isBlank(map.get("mileage")) && parsed.mileage() == null) errorFields.add("mileage");
        if (!isBlank(map.get("displacement")) && parsed.displacement() == null) errorFields.add("displacement");
        if (!isBlank(map.get("initialRegistrationDate")) && parsed.initialRegistrationDate() == null)
            errorFields.add("initialRegistrationDate");

        log.info("[Auction] Parsed: {}", parsed);
        if (!missing.isEmpty()) log.warn("[Auction] Missing: {}", missing);

        return new AuctionParseResult(parsed, missing, errorFields);
    }

    // ─── 유틸리티 ───

    private String firstNumber(String s) {
        if (s == null) return null;
        Matcher m = FIRST_NUMBER.matcher(s.replaceAll(",", ""));
        return m.find() ? m.group(1) : null;
    }

    private boolean match(String text, List<String> labels) {
        String n = text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
        return labels.stream().anyMatch(l -> n.contains(l.replaceAll("\\s+", "").toLowerCase()));
    }

    private String clean(String s) { return s == null ? null : s.trim().isBlank() ? null : s.trim(); }

    private Integer parseYear(String raw) {
        if (isBlank(raw)) return null;
        String v = raw.replaceAll("[^0-9]", "");
        if (v.length() < 2) return null;
        try { int y = Integer.parseInt(v.substring(0, Math.min(4, v.length()))); return y < 100 ? 2000 + y : y; }
        catch (NumberFormatException e) { return null; }
    }

    private Long parseLong(String raw) {
        if (isBlank(raw)) return null;
        String v = raw.replaceAll("[^0-9]", "");
        try { return v.isEmpty() ? null : Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }

    private Integer parseInteger(String raw) {
        if (isBlank(raw)) return null;
        String v = raw.replaceAll("[^0-9]", "");
        try { return v.isEmpty() ? null : Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseDateFlexible(String raw) {
        if (isBlank(raw)) return null;
        String normalized = raw.replaceAll("[^0-9]", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        for (DateTimeFormatter f : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("yyyy-MM"), DateTimeFormatter.ofPattern("yyyy-M"))) {
            try { return LocalDate.parse(normalized, f); } catch (Exception ignored) {}
        }
        return null;
    }

    private void validateField(Map<String, String> map, String key, Pattern pattern, List<String> errors) {
        String v = map.get(key);
        if (!isBlank(v) && !pattern.matcher(v.replaceAll("\\s+", "")).matches()) errors.add(key);
    }

    private void validateVin(Map<String, String> map, List<String> errors) {
        String v = map.get("chassisNo");
        if (!isBlank(v) && !VIN_PATTERN.matcher(v.replaceAll("\\s+", "").toUpperCase()).matches()) errors.add("chassisNo");
    }
}
