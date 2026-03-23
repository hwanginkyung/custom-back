package exps.cariv.domain.shipper.service;

import exps.cariv.domain.shipper.dto.ParsedIdCard;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static exps.cariv.global.parser.ParserUtils.isBlank;
import static exps.cariv.global.parser.ParserUtils.nextNonBlank;
import static exps.cariv.global.parser.ParserUtils.parseHtmlTable;
import static exps.cariv.global.parser.ParserUtils.putIfAbsent;

/**
 * 신분증 OCR 파서 (내부 저장용).
 * 주민등록증 또는 운전면허증.
 */
@Service
@Slf4j
public class IdCardParserService {

    private static final List<String> NAME_LABELS = List.of("성명", "이름");
    private static final List<String> ID_LABELS = List.of("주민등록번호", "주민번호");
    private static final List<String> ADDRESS_LABELS = List.of("주소");
    private static final List<String> ISSUE_DATE_LABELS = List.of("발급일", "발행일");
    private static final List<String> NAME_NOISE = List.of(
            "주민등록", "운전면허", "대한민국", "republic", "korea", "신분증", "발급일", "주소", "번호"
    );

    private static final Pattern KOR_ID_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})[- ]?(\\d{7})(?!\\d)");
    private static final Pattern ISSUE_DATE_PATTERN = Pattern.compile(
            "(?<!\\d)((19|20)\\d{2})[.\\-/년\\s]+(\\d{1,2})[.\\-/월\\s]+(\\d{1,2})(?:일)?"
    );
    private static final Pattern KOR_NAME_PATTERN = Pattern.compile("[가-힣]{2,10}");

    public ParsedIdCard parse(UpstageResponse res) {
        Map<String, String> map = new HashMap<>();

        List<UpstageElement> elements = Optional.ofNullable(res)
                .map(UpstageResponse::elements)
                .orElse(Collections.emptyList());

        // 1) 테이블 요소에서 라벨 기반 추출
        List<UpstageElement> tableElements = elements.stream()
                .filter(e -> "table".equalsIgnoreCase(e.category()))
                .filter(e -> e.content() != null && e.content().html() != null)
                .toList();

        for (UpstageElement table : tableElements) {
            for (List<String> row : parseHtmlTable(table.content().html())) {
                for (int i = 0; i < row.size(); i++) {
                    String cell = row.get(i);
                    if (isBlank(cell)) continue;
                    String next = nextNonBlank(row, i + 1);

                    if (matchLabel(cell, NAME_LABELS)) putIfAbsent(map, "holderName", next);
                    if (matchLabel(cell, ID_LABELS)) putIfAbsent(map, "idNumber", maskId(next));
                    if (matchLabel(cell, ADDRESS_LABELS)) putIfAbsent(map, "idAddress", next);
                    if (matchLabel(cell, ISSUE_DATE_LABELS)) putIfAbsent(map, "issueDate", next);
                }
            }
        }

        // 2) paragraph/html까지 포함한 fallback 추출
        String rawText = collectRawText(elements);
        List<String> lines = splitLines(rawText);

        if (isBlank(map.get("idNumber"))) {
            String rawId = findIdNumber(rawText);
            if (rawId != null) {
                map.put("idNumber", maskId(rawId));
            }
        }

        if (isBlank(map.get("holderName"))) {
            String holder = findHolderName(lines);
            if (holder != null) {
                map.put("holderName", holder);
            }
        }

        if (isBlank(map.get("idAddress"))) {
            String address = findAddress(lines);
            if (address != null) {
                map.put("idAddress", address);
            }
        }

        if (isBlank(map.get("issueDate"))) {
            String issueDate = findIssueDate(rawText, lines);
            if (issueDate != null) {
                map.put("issueDate", issueDate);
            }
        }

        ParsedIdCard parsed = new ParsedIdCard(
                map.get("holderName"), map.get("idNumber"),
                map.get("idAddress"), map.get("issueDate")
        );
        log.info("[IdCard] Parsed: {}", parsed);
        return parsed;
    }

    /**
     * 주민번호 뒷자리 마스킹 (000000-1234567 → 000000-1******)
     */
    private String maskId(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() >= 13) {
            String normalized = digits.substring(0, 6) + "-" + digits.charAt(6);
            return normalized + "******";
        }
        String compact = raw.replaceAll("\\s+", "");
        return compact.isBlank() ? null : compact;
    }

    private boolean matchLabel(String text, List<String> labels) {
        if (text == null) return false;
        String n = text.replaceAll("\\s+", "").replace(":", "").replace("：", "").toLowerCase();
        if (n.isEmpty() || n.length() > 15) return false;
        for (String label : labels) {
            String ln = label.replaceAll("\\s+", "").toLowerCase();
            if (n.equals(ln) || n.contains(ln)) return true;
        }
        return false;
    }

    private String collectRawText(List<UpstageElement> elements) {
        if (elements == null || elements.isEmpty()) return "";
        return elements.stream()
                .map(UpstageElement::content)
                .filter(Objects::nonNull)
                .flatMap(c -> Stream.of(
                        c.text(),
                        c.markdown(),
                        stripHtmlKeepLineBreaks(c.html())
                ))
                .filter(Objects::nonNull)
                .filter(v -> !v.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\\R+"))
                .map(this::normalizeSpaces)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private String findIdNumber(String rawText) {
        if (isBlank(rawText)) return null;
        Matcher m = KOR_ID_PATTERN.matcher(rawText);
        if (!m.find()) return null;
        return m.group(1) + "-" + m.group(2);
    }

    private String findHolderName(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        int idLineIdx = findLineIndex(lines, KOR_ID_PATTERN);
        if (idLineIdx > 0) {
            for (int i = idLineIdx - 1; i >= 0; i--) {
                String candidate = sanitizeName(lines.get(i));
                if (candidate != null) return candidate;
            }
        }

        for (String line : lines) {
            String candidate = sanitizeName(line);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private String findAddress(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        int idLineIdx = findLineIndex(lines, KOR_ID_PATTERN);
        if (idLineIdx < 0 || idLineIdx + 1 >= lines.size()) return null;

        List<String> chunks = new ArrayList<>();
        for (int i = idLineIdx + 1; i < lines.size(); i++) {
            String line = normalizeSpaces(lines.get(i));
            if (line.isBlank()) continue;

            if (ISSUE_DATE_PATTERN.matcher(line).find()) break;
            if (looksLikeIssuer(line)) break;
            if (looksLikeAddressNoise(line)) continue;

            chunks.add(line);
        }

        if (chunks.isEmpty()) return null;
        return normalizeAddress(String.join(" ", chunks));
    }

    private String findIssueDate(String rawText, List<String> lines) {
        if (lines != null && !lines.isEmpty()) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                Matcher m = ISSUE_DATE_PATTERN.matcher(lines.get(i));
                if (m.find()) return toIsoDate(m);
            }
        }

        if (!isBlank(rawText)) {
            Matcher m = ISSUE_DATE_PATTERN.matcher(rawText);
            if (m.find()) return toIsoDate(m);
        }
        return null;
    }

    private int findLineIndex(List<String> lines, Pattern p) {
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).find()) return i;
        }
        return -1;
    }

    private String sanitizeName(String raw) {
        if (isBlank(raw)) return null;
        String line = normalizeSpaces(raw);
        String lower = line.toLowerCase(Locale.ROOT);

        for (String noise : NAME_NOISE) {
            if (lower.contains(noise)) return null;
        }
        if (line.matches(".*\\d.*")) return null;
        if (line.length() > 20) return null;

        String withoutLabel = line.replaceFirst("^(성명|이름)\\s*[:：]?\\s*", "").trim();
        String withoutHanja = withoutLabel.replaceAll("\\([^)]*\\)", "").trim();

        Matcher m = KOR_NAME_PATTERN.matcher(withoutHanja);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    private boolean looksLikeIssuer(String line) {
        String normalized = line.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return normalized.contains("시장")
                || normalized.contains("도지사")
                || normalized.contains("구청장")
                || normalized.contains("군수")
                || normalized.contains("읍장")
                || normalized.contains("면장")
                || normalized.contains("동장")
                || normalized.contains("police");
    }

    private boolean looksLikeAddressNoise(String line) {
        String normalized = line.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return normalized.contains("주민등록")
                || normalized.contains("운전면허")
                || normalized.contains("republic")
                || normalized.contains("korea");
    }

    private String normalizeAddress(String raw) {
        if (isBlank(raw)) return null;
        String normalized = normalizeSpaces(raw)
                .replaceAll("\\s+,", ",")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")");
        return normalized.isBlank() ? null : normalized;
    }

    private String toIsoDate(Matcher m) {
        int y = Integer.parseInt(m.group(1));
        int mo = Integer.parseInt(m.group(3));
        int d = Integer.parseInt(m.group(4));
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo, d);
    }

    private String stripHtmlKeepLineBreaks(String html) {
        if (isBlank(html)) return null;
        return html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<p[^>]*>", "")
                .replaceAll("(?i)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'");
    }

    private String normalizeSpaces(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

}
