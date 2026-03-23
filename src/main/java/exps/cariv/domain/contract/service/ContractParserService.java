package exps.cariv.domain.contract.service;

import exps.cariv.domain.contract.dto.ContractParseResult;
import exps.cariv.domain.contract.dto.ContractParsed;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static exps.cariv.global.parser.ParserUtils.*;

/**
 * 매매계약서 OCR 파싱 서비스.
 * 공통 유틸은 ParserUtils 사용.
 */
@Service
@Slf4j
public class ContractParserService {

    private static final List<String> REG_NO_LABELS = List.of("자동차등록번호", "차량번호");
    private static final List<String> VEHICLE_TYPE_LABELS = List.of("차종", "차 종");
    private static final List<String> MODEL_LABELS = List.of("차명", "차 명");
    private static final List<String> VIN_LABELS = List.of("차대번호", "차 대 번 호", "차대 번호", "차 대 번호");

    private static final List<String> REQUIRED_KEYS = List.of("registrationNo", "vehicleType", "model", "chassisNo");

    public ContractParseResult parseAndValidate(UpstageResponse res) {
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

                if (matchLabel(cell, REG_NO_LABELS)) {
                    putIfAbsent(map, "registrationNo",
                            firstNonBlank(normalize(next), normalize(extractInlineAfter(cell, "자동차등록번호")),
                                    findPlate(cell), findPlate(next)));
                }
                if (matchLabel(cell, VEHICLE_TYPE_LABELS)) {
                    putIfAbsent(map, "vehicleType", normalize(firstNonBlank(normalize(next), normalize(extractInlineAfter(cell, "차종")))));
                }
                if (matchLabel(cell, MODEL_LABELS)) {
                    putIfAbsent(map, "model", normalize(firstNonBlank(normalize(next), normalize(extractInlineAfter(cell, "차명")))));
                }
                if (matchLabel(cell, VIN_LABELS)) {
                    String raw = firstNonBlank(normalize(next), normalize(extractInlineAfter(cell, "차대번호")));
                    String vin = firstNonBlank(extractVin(raw), extractVin(cell));
                    putIfAbsent(map, "chassisNo", firstNonBlank(vin, raw));
                }
            }
        }

        if (isBlank(map.get("registrationNo"))) map.put("registrationNo", findPlate(allText));
        if (isBlank(map.get("chassisNo"))) {
            String vin = extractVin(allText);
            if (!isBlank(vin)) map.put("chassisNo", vin);
        }

        ContractParsed parsed = new ContractParsed(
                map.get("registrationNo"),
                map.get("vehicleType"),
                map.get("model"),
                map.get("chassisNo")
        );

        List<String> missing = REQUIRED_KEYS.stream().filter(k -> isMissing(map.get(k))).toList();

        List<String> errorFields = new ArrayList<>();
        if (!isBlank(map.get("registrationNo")) && !PLATE_PATTERN.matcher(map.get("registrationNo").replaceAll("\\s+", "")).matches())
            errorFields.add("registrationNo");
        if (!isBlank(map.get("chassisNo")) && !VIN_PATTERN.matcher(map.get("chassisNo").replaceAll("\\s+", "").toUpperCase()).matches())
            errorFields.add("chassisNo");

        log.info("[Contract] Parsed: {}", parsed);
        if (!missing.isEmpty()) log.warn("[Contract] Missing: {}", missing);

        return new ContractParseResult(parsed, missing, errorFields);
    }
}
