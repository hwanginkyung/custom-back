package exps.cariv.domain.export.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 수출신고필증 OCR 파싱 결과.
 */
public record ExportParseResult(
        ExportInfo info,
        List<String> missingFields,
        List<String> errorFields
) {

    public record ExportInfo(
            String declarationNo,
            LocalDate declarationDate,
            LocalDate acceptanceDate,
            String issueNo,
            String destCountryCode,
            String destCountryName,
            String loadingPortCode,
            String loadingPortName,
            String containerNo,
            String itemName,
            String modelYear,
            String chassisNo,
            Long amountKrw,
            LocalDate loadingDeadline,
            String buyerName
    ) {}
}
