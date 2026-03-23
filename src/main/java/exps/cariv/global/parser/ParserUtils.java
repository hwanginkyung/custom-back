package exps.cariv.global.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OCR 파서 공통 유틸리티.
 *
 * Registration, Contract, Auction 등 문서별 파서 서비스에서 공유하는
 * 문자열 정규화, VIN/번호판 추출, HTML 테이블 파싱 등의 공통 로직.
 *
 * 각 파서 서비스는 이 유틸을 static import하여 사용한다.
 */
public final class ParserUtils {

    private ParserUtils() {}

    // ──────────────────────────────────────────────────────────────
    // 정규식 패턴 (공통)
    // ──────────────────────────────────────────────────────────────

    /** VIN (Vehicle Identification Number) 17자리 패턴 */
    public static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    /** 한국 자동차 번호판 패턴 (예: 12가3456, 123가4567) */
    public static final Pattern PLATE_PATTERN = Pattern.compile("\\b\\d{2,3}[가-힣]\\d{4}\\b");

    /** 주민등록번호/법인등록번호 패턴 (마스킹 감지용) */
    public static final Pattern KOR_ID_PATTERN = Pattern.compile("\\b\\d{6}-\\d{7}\\b");

    /** 날짜 패턴: 2024.01.15, 2024-01-15, 2024/01/15 */
    public static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})\\b");

    /** 숫자 추출용 (첫 번째 숫자) */
    public static final Pattern FIRST_NUMBER = Pattern.compile("(\\d{1,6})");

    // ──────────────────────────────────────────────────────────────
    // 문자열 정규화
    // ──────────────────────────────────────────────────────────────

    /** null-safe trim, 빈 문자열은 null 반환 */
    public static String normalize(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** VIN 등 식별자용: 공백 제거 + 대문자 변환, 빈 값은 null */
    public static String normalizeId(String v) {
        if (v == null) return null;
        String cleaned = v.replaceAll("\\s+", "").toUpperCase();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** 라벨 정규화: 공백/콜론 제거 + 소문자 */
    public static String normalizeLabel(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").replace(":", "").replace("：", "").toLowerCase();
    }

    /** null이거나 공백만 있으면 true */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 인자 중 첫 번째 non-blank 값 반환, 없으면 null */
    public static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) return v;
        }
        return null;
    }

    /** 값이 비어있거나 "0", "O" 등 무의미한 값이면 true */
    public static boolean isMissing(String v) {
        if (isBlank(v)) return true;
        String n = v.replaceAll("\\s+", "").toLowerCase();
        return n.equals("0") || n.equals("o");
    }

    // ──────────────────────────────────────────────────────────────
    // 패턴 추출
    // ──────────────────────────────────────────────────────────────

    /** 문자열에서 17자리 VIN 추출, 없으면 null */
    public static String extractVin(String s) {
        if (s == null) return null;
        Matcher m = VIN_PATTERN.matcher(s.replaceAll("\\s+", "").toUpperCase());
        return m.find() ? m.group() : null;
    }

    /** 문자열에서 한국 번호판 추출, 없으면 null */
    public static String findPlate(String s) {
        if (s == null) return null;
        Matcher m = PLATE_PATTERN.matcher(s.replaceAll("\\s+", ""));
        return m.find() ? m.group() : null;
    }

    /** 셀에서 키워드 뒤의 인라인 값 추출 (예: "차종승용" → "승용") */
    public static String extractInlineAfter(String cell, String keyword) {
        if (cell == null) return null;
        String compact = cell.replaceAll("\\s+", "");
        String key = keyword.replaceAll("\\s+", "");
        int idx = compact.indexOf(key);
        if (idx < 0) return null;
        String after = compact.substring(idx + key.length());
        return after.isBlank() ? null : after;
    }

    // ──────────────────────────────────────────────────────────────
    // 라벨 매칭
    // ──────────────────────────────────────────────────────────────

    /**
     * 셀 텍스트가 주어진 라벨 목록 중 하나와 매치되는지 판별.
     * 짧은 셀(12자 이하)만 매치하며, 주민등록/사업자 오탐 방지.
     */
    public static boolean matchLabel(String text, List<String> labels) {
        if (text == null) return false;
        String n = normalizeLabel(text);
        if (n.isEmpty() || n.length() > 12) return false;
        if (n.contains("주민등록") || n.contains("사업자")) return false;
        for (String label : labels) {
            String ln = normalizeLabel(label);
            if (n.equals(ln)) return true;
            if (n.startsWith(ln) && n.length() <= ln.length() + 2) return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────
    // HTML 테이블 파싱
    // ──────────────────────────────────────────────────────────────

    /**
     * HTML 테이블을 2차원 문자열 리스트로 파싱.
     * 중첩 테이블이 포함된 단일 셀 행은 스킵.
     */
    public static List<List<String>> parseHtmlTable(String html) {
        List<List<String>> rows = new ArrayList<>();
        if (html == null || html.isBlank()) return rows;
        Document doc = Jsoup.parse(html);
        for (Element tr : doc.select("tr")) {
            Elements cells = tr.select("> th, > td");
            if (cells.isEmpty()) continue;
            if (cells.size() == 1 && !cells.get(0).select("table").isEmpty()) continue;
            List<String> row = cells.stream()
                    .map(Element::text)
                    .map(String::trim)
                    .collect(Collectors.toList());
            if (row.stream().anyMatch(v -> !isBlank(v))) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 행 중복 제거 (삽입 순서 유지) */
    public static List<List<String>> uniqueRows(List<List<String>> rows) {
        List<List<String>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<String> row : rows) {
            if (seen.add(String.join("||", row))) {
                result.add(row);
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────
    // 테이블 행 탐색 헬퍼
    // ──────────────────────────────────────────────────────────────

    /** 행에서 start 인덱스 이후 첫 번째 non-blank 셀 반환 */
    public static String nextNonBlank(List<String> row, int start) {
        for (int i = start; i < row.size(); i++) {
            if (!isBlank(row.get(i))) return row.get(i);
        }
        return null;
    }

    /** Map에 키가 비어있을 때만 값 설정 */
    public static void putIfAbsent(Map<String, String> map, String key, String value) {
        if (isBlank(map.get(key)) && !isBlank(value)) {
            map.put(key, value.trim());
        }
    }
}
