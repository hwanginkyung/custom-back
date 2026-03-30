package exps.cariv.domain.shipper.dto.request;

/**
 * 신분증 OCR 스냅샷 수동 수정 요청.
 */
public record IdCardSnapshotUpdateRequest(
        String holderName,
        String idNumber,
        String idAddress,
        String issueDate,
        Integer rotateDegrees
) {}
