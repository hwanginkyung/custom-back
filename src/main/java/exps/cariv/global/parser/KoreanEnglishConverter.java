package exps.cariv.global.parser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Invoice/Packing List 출력용 한글 -> 영문(ASCII) 변환 유틸.
 * <p>
 * 외부 번역 API 없이 동작하며,
 * 1) 일부 고정 사전 치환
 * 2) 괄호 내 영문명 우선 추출 (예: 투싼(TUCSON) -> TUCSON)
 * 3) 잔여 한글은 로마자 표기
 * 순서로 처리한다.
 */
public final class KoreanEnglishConverter {

    private KoreanEnglishConverter() {
    }

    private static final Pattern HANGUL_PATTERN = Pattern.compile("[가-힣]");
    private static final Pattern ASCII_IN_PAREN_PATTERN = Pattern.compile("\\(([A-Za-z0-9\\s\\-_/.,&]+)\\)");

    private static final String[] CHOSEONG = {
            "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"
    };
    private static final String[] JUNGSEONG = {
            "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    };
    private static final String[] JONGSEONG = {
            "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t"
    };

    private static final Map<String, String> DIRECT_REPLACEMENTS = buildDirectReplacements();
    private static final Map<String, String> FUEL_REPLACEMENTS = buildFuelReplacements();

    public static String toEnglish(String value) {
        return convert(value, false);
    }

    public static String toEnglishVehicleName(String value) {
        return convert(value, true);
    }

    public static String toEnglishFuel(String value) {
        if (isBlank(value)) {
            return value;
        }
        String normalized = value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> entry : FUEL_REPLACEMENTS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return toEnglish(normalized);
    }

    private static String convert(String value, boolean preferAsciiInParentheses) {
        if (isBlank(value)) {
            return value;
        }
        String normalized = value.trim();

        if (preferAsciiInParentheses) {
            String asciiHint = extractAsciiInParentheses(normalized);
            if (!isBlank(asciiHint)) {
                return cleanupSpacing(asciiHint);
            }
        }

        normalized = applyDirectReplacements(normalized);
        if (!containsHangul(normalized)) {
            return cleanupSpacing(normalized);
        }

        String romanized = romanizeHangul(normalized);
        romanized = cleanupSpacing(romanized);
        return titleizeWords(romanized);
    }

    private static String extractAsciiInParentheses(String value) {
        Matcher matcher = ASCII_IN_PAREN_PATTERN.matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!isBlank(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String applyDirectReplacements(String value) {
        String result = value;
        for (Map.Entry<String, String> entry : DIRECT_REPLACEMENTS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static boolean containsHangul(String value) {
        return value != null && HANGUL_PATTERN.matcher(value).find();
    }

    private static String romanizeHangul(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 0xAC00 && ch <= 0xD7A3) {
                int syllableIndex = ch - 0xAC00;
                int cho = syllableIndex / 588;
                int jung = (syllableIndex % 588) / 28;
                int jong = syllableIndex % 28;
                out.append(CHOSEONG[cho])
                        .append(JUNGSEONG[jung])
                        .append(JONGSEONG[jong]);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String cleanupSpacing(String value) {
        if (value == null) return null;
        return value
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.)])", "$1")
                .replaceAll("([(])\\s+", "$1")
                .trim();
    }

    private static String titleizeWords(String value) {
        if (isBlank(value)) return value;
        String[] tokens = value.split(" ");
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            String converted = token;
            if (token.matches("[a-z][a-z0-9\\-]*")) {
                converted = Character.toUpperCase(token.charAt(0)) + token.substring(1);
            }
            if (i > 0) out.append(' ');
            out.append(converted);
        }
        return out.toString();
    }

    private static Map<String, String> buildDirectReplacements() {
        Map<String, String> map = new LinkedHashMap<>();

        map.put("(주)", " Co., Ltd. ");
        map.put("주식회사", " Co., Ltd. ");
        map.put("유한회사", " Ltd. ");
        map.put("무역", " Trading ");
        map.put("상사", " Trading ");
        map.put("자동차", " Motors ");

        map.put("대한민국", "Republic of Korea");
        map.put("서울특별시", "Seoul");
        map.put("부산광역시", "Busan");
        map.put("대구광역시", "Daegu");
        map.put("인천광역시", "Incheon");
        map.put("광주광역시", "Gwangju");
        map.put("대전광역시", "Daejeon");
        map.put("울산광역시", "Ulsan");
        map.put("세종특별자치시", "Sejong");
        map.put("경기도", "Gyeonggi-do");
        map.put("강원특별자치도", "Gangwon-do");
        map.put("충청북도", "Chungcheongbuk-do");
        map.put("충청남도", "Chungcheongnam-do");
        map.put("전라북도", "Jeollabuk-do");
        map.put("전라남도", "Jeollanam-do");
        map.put("경상북도", "Gyeongsangbuk-do");
        map.put("경상남도", "Gyeongsangnam-do");
        map.put("제주특별자치도", "Jeju-do");

        return map;
    }

    private static Map<String, String> buildFuelReplacements() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("diesel", "Diesel");
        map.put("경유", "Diesel");
        map.put("가솔린", "Gasoline");
        map.put("휘발유", "Gasoline");
        map.put("petrol", "Gasoline");
        map.put("lpg", "LPG");
        map.put("전기", "Electric");
        map.put("electric", "Electric");
        map.put("하이브리드", "Hybrid");
        map.put("hybrid", "Hybrid");
        map.put("수소", "Hydrogen");
        map.put("hydrogen", "Hydrogen");
        map.put("기타", "Other");
        map.put("etc", "Other");
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
