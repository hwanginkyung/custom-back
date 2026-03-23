package exps.cariv.domain.shipper.dto;

/**
 * 신분증 OCR 스냅샷.
 */
public record IdCardSnapshot(
        String holderName,
        String idNumber,
        String idAddress,
        String issueDate
) {}
