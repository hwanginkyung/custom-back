package exps.cariv.domain.export.service;

import exps.cariv.domain.export.dto.ExportParseResult;
import exps.cariv.domain.export.dto.ExportParseResult.ExportInfo;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static exps.cariv.global.parser.ParserUtils.isBlank;
import static exps.cariv.global.parser.ParserUtils.parseHtmlTable;
import static exps.cariv.global.parser.ParserUtils.uniqueRows;

/**
 * 수출신고필증 OCR 결과를 파싱하여 구조화된 데이터로 변환.
 * <p>참고: cariv.exp.domain.export.service.ExportParserService 패턴 포팅.</p>
 */
@Service
@Slf4j
public class ExportParserService {

    private static final Pattern DECLARATION_NO_PATTERN =
            Pattern.compile("신고번호\\s*(\\d{5}-\\d{2}-\\d{6}[A-Z]?)");

    private static final Pattern DECLARATION_DATE_PATTERN =
            Pattern.compile("신고일자\\s*(\\d{4}-\\d{2}-\\d{2})");

    private static final Pattern ACCEPTANCE_DATE_PATTERN =
            Pattern.compile("신고수리일자\\s*(\\d{4}[/-]\\d{2}[/-]\\d{2})");

    private static final Pattern LOADING_DEADLINE_PATTERN =
            Pattern.compile("적재의무기한\\s*(\\d{4}[/-]\\d{2}[/-]\\d{2})");

    private static final Pattern DEST_PATTERN =
            Pattern.compile("목적국\\s*\\(?.*?\\)?\\s*([A-Z]{2,3})\\s*([A-Z]{2,}(?:\\s+[A-Z]{2,})*)");

    private static final Pattern LOADING_PORT_PATTERN =
            Pattern.compile("적재항\\s*([A-Z]{4,5})\\s*([가-힣]{2,10}항)");

    private static final Pattern CONTAINER_PATTERN =
            Pattern.compile("컨테이너번호\\s*([A-Z]{4}\\d{7})");

    private static final Pattern ITEM_NAME_PATTERN =
            Pattern.compile("거래품명\\s*([A-Z0-9\\-]{2,})");

    private static final Pattern YEAR_VIN_PATTERN =
            Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b\\s+\\b([A-HJ-NPR-Z0-9]{17})\\b");

    private static final Pattern ISSUE_NO_PATTERN =
            Pattern.compile("발\\s*행\\s*번\\s*호\\s*[:：]?\\s*(\\d{8,})");

    private static final Pattern FOB_KRW_PATTERN =
            Pattern.compile("FOB\\s*-?\\s*KRW\\s*-\\s*([0-9,]+)(?:\\.\\d+)?");

    private static final Pattern BUYER_PATTERN =
            Pattern.compile("구\\s*매\\s*자\\s*([A-Z][A-Z\\s]{2,})\\s*\\(구매자부호\\)");

    private static final List<String> ALL_KEYS = List.of(
            "declarationNo", "declarationDate", "acceptanceDate", "issueNo",
            "destCountryCode", "destCountryName", "loadingPortCode", "loadingPortName",
            "containerNo", "itemName", "modelYear", "chassisNo", "amountKrw",
            "loadingDeadline", "buyerName"
    );

    public ExportParseResult parse(UpstageResponse json) {
        Map<String, String> map = new HashMap<>();

        // 1) table html → rows
        List<UpstageElement> tableElements = json.elements().stream()
                .filter(e -> "table".equalsIgnoreCase(e.category()))
                .filter(e -> e.content() != null && e.content().html() != null && !e.content().html().isBlank())
                .toList();

        List<List<String>> allRows = new ArrayList<>();
        for (UpstageElement table : tableElements) {
            allRows.addAll(parseHtmlTable(table.content().html()));
        }
        allRows = uniqueRows(allRows);

        // 2) blob(전체 텍스트)
        String blob = String.join(" ", allRows.stream().flatMap(List::stream).toList());
        blob = normalize(blob);

        // 3) issueNo 문서 전체 텍스트에서 보강
        String allText = json.elements().stream()
                .map(e -> e.content() != null ? e.content().html() : null)
                .filter(Objects::nonNull)
                .map(h -> Jsoup.parse(h).text())
                .collect(Collectors.joining(" "));
        allText = normalize(allText);
        putIfMatch(map, "issueNo", ISSUE_NO_PATTERN, allText, 1);

        // 4) 라벨 앵커 추출
        putIfMatch(map, "declarationNo", DECLARATION_NO_PATTERN, blob, 1);
        putIfMatch(map, "declarationDate", DECLARATION_DATE_PATTERN, blob, 1);
        putIfMatch(map, "acceptanceDate", ACCEPTANCE_DATE_PATTERN, blob, 1);
        putIfMatch(map, "loadingDeadline", LOADING_DEADLINE_PATTERN, blob, 1);

        Matcher md = DEST_PATTERN.matcher(blob);
        if (md.find()) {
            map.putIfAbsent("destCountryCode", md.group(1));
            map.putIfAbsent("destCountryName", md.group(2));
        }

        Matcher mp = LOADING_PORT_PATTERN.matcher(blob);
        if (mp.find()) {
            map.putIfAbsent("loadingPortCode", mp.group(1));
            map.putIfAbsent("loadingPortName", mp.group(2));
        }

        putIfMatch(map, "containerNo", CONTAINER_PATTERN, blob, 1);
        putIfMatch(map, "itemName", ITEM_NAME_PATTERN, blob, 1);

        Matcher myv = YEAR_VIN_PATTERN.matcher(blob);
        if (myv.find()) {
            map.putIfAbsent("modelYear", myv.group(1));
            map.putIfAbsent("chassisNo", myv.group(2));
        }

        String fob = find(blob, FOB_KRW_PATTERN, 1);
        if (!isBlank(fob)) map.put("amountKrw", String.valueOf(parseMoney(fob)));

        putIfMatch(map, "buyerName", BUYER_PATTERN, blob, 1);

        // 날짜 파싱
        LocalDate declarationDate = parseDateFlexible(map.get("declarationDate"));
        LocalDate acceptanceDate  = parseDateFlexible(map.get("acceptanceDate"));
        LocalDate loadingDeadline = parseDateFlexible(map.get("loadingDeadline"));

        if (map.get("declarationDate") != null && declarationDate == null) map.remove("declarationDate");
        if (map.get("acceptanceDate")  != null && acceptanceDate  == null) map.remove("acceptanceDate");
        if (map.get("loadingDeadline") != null && loadingDeadline == null) map.remove("loadingDeadline");

        ExportInfo info = new ExportInfo(
                map.get("declarationNo"),
                declarationDate,
                acceptanceDate,
                map.get("issueNo"),
                map.get("destCountryCode"),
                map.get("destCountryName"),
                map.get("loadingPortCode"),
                map.get("loadingPortName"),
                map.get("containerNo"),
                map.get("itemName"),
                map.get("modelYear"),
                map.get("chassisNo"),
                map.get("amountKrw") == null ? null : Long.parseLong(map.get("amountKrw")),
                loadingDeadline,
                map.get("buyerName")
        );

        List<String> missing = ALL_KEYS.stream()
                .filter(k -> isBlank(map.get(k)))
                .toList();

        return new ExportParseResult(info, missing, List.of());
    }

    // ===== helpers =====
    private void putIfMatch(Map<String, String> map, String key, Pattern p, String text, int group) {
        if (map.containsKey(key)) return;
        String v = find(text, p, group);
        if (!isBlank(v)) map.put(key, v);
    }

    private String find(String text, Pattern p, int group) {
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        return m.group(group);
    }

    private String normalize(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\[c\\]\\d+\\[c\\]", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private long parseMoney(String s) {
        String t = s.replaceAll("[^0-9]", "");
        if (t.isBlank()) return 0L;
        return Long.parseLong(t);
    }

    private LocalDate parseDateFlexible(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().replace('.', '-').replace('/', '-');
        List<DateTimeFormatter> fs = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("yyyy-MM-d"),
                DateTimeFormatter.ofPattern("yyyy-M-dd")
        );
        for (DateTimeFormatter f : fs) {
            try { return LocalDate.parse(v, f); } catch (Exception ignored) {}
        }
        return null;
    }
}
