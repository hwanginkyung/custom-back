package exps.cariv.domain.export.dto.request;

import java.time.LocalDate;

/**
 * 수출신고필증 OCR 스냅샷 수동 수정 요청.
 */
public record ExportSnapshotUpdateRequest(
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
