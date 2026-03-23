package exps.cariv.domain.shipper.service;

import exps.cariv.domain.shipper.dto.ParsedBizReg;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static exps.cariv.global.parser.ParserUtils.isBlank;
import static exps.cariv.global.parser.ParserUtils.nextNonBlank;
import static exps.cariv.global.parser.ParserUtils.parseHtmlTable;
import static exps.cariv.global.parser.ParserUtils.putIfAbsent;

/**
 * 사업자등록증 OCR 파서 (내부 저장용).
 */
@Service
@Slf4j
public class BizRegParserService {

    private static final List<String> COMPANY_NAME_LABELS = List.of("상호", "법인명", "상호(법인명)");
    private static final List<String> REP_NAME_LABELS = List.of("대표자", "성명");
    private static final List<String> BIZ_NUMBER_LABELS = List.of("사업자등록번호", "등록번호(사업자등록번호)");
    private static final List<String> BIZ_TYPE_LABELS = List.of("업태");
    private static final List<String> BIZ_CATEGORY_LABELS = List.of("종목", "업종");
    private static final List<String> ADDRESS_LABELS = List.of("사업장소재지", "사업장 소재지", "소재지");
    private static final List<String> OPEN_DATE_LABELS = List.of("개업연월일", "개업 연월일");
    private static final Pattern BIZ_NUMBER_PATTERN = Pattern.compile("(?<!\\d)(\\d{3})[-\\s]?(\\d{2})[-\\s]?(\\d{5})(?!\\d)");

    public ParsedBizReg parse(UpstageResponse res) {
        Map<String, String> map = new HashMap<>();

        List<UpstageElement> tableElements = res.elements().stream()
                .filter(e -> "table".equalsIgnoreCase(e.category()))
                .filter(e -> e.content() != null && e.content().html() != null)
                .toList();

        for (UpstageElement table : tableElements) {
            for (List<String> row : parseHtmlTable(table.content().html())) {
                for (int i = 0; i < row.size(); i++) {
                    String cell = row.get(i);
                    if (isBlank(cell)) continue;
                    String next = nextNonBlank(row, i + 1);

                    if (matchLabel(cell, COMPANY_NAME_LABELS)) putIfAbsent(map, "companyName", next);
                    if (matchLabel(cell, REP_NAME_LABELS)) putIfAbsent(map, "representativeName", next);
                    if (matchLabel(cell, BIZ_NUMBER_LABELS)) {
                        String bizNumber = firstNonBlank(
                                extractBizNumber(next),
                                extractBizNumber(cell),
                                next
                        );
                        putIfAbsent(map, "bizNumber", bizNumber);
                    }
                    if (matchLabel(cell, BIZ_TYPE_LABELS)) putIfAbsent(map, "bizType", next);
                    if (matchLabel(cell, BIZ_CATEGORY_LABELS)) putIfAbsent(map, "bizCategory", next);
                    if (matchLabel(cell, ADDRESS_LABELS)) putIfAbsent(map, "bizAddress", next);
                    if (matchLabel(cell, OPEN_DATE_LABELS)) putIfAbsent(map, "openDate", next);
                }
            }
        }

        ParsedBizReg parsed = new ParsedBizReg(
                map.get("companyName"), map.get("representativeName"),
                map.get("bizNumber"), map.get("bizType"), map.get("bizCategory"),
                map.get("bizAddress"), map.get("openDate")
        );
        log.info("[BizReg] Parsed: {}", parsed);
        return parsed;
    }

    private boolean matchLabel(String text, List<String> labels) {
        if (text == null) return false;
        String n = text.replaceAll("\\s+", "").replace(":", "").replace("：", "").toLowerCase();
        if (n.isEmpty() || n.length() > 80) return false;
        for (String label : labels) {
            String ln = label.replaceAll("\\s+", "").toLowerCase();
            if (n.equals(ln) || n.contains(ln)) return true;
        }
        return false;
    }

    private String extractBizNumber(String text) {
        if (isBlank(text)) return null;
        Matcher matcher = BIZ_NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
