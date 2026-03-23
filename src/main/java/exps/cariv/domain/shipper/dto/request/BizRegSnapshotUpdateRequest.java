package exps.cariv.domain.shipper.dto.request;

/**
 * 사업자등록증 OCR 스냅샷 수동 수정 요청.
 */
public record BizRegSnapshotUpdateRequest(
        String companyName,
        String representativeName,
        String bizNumber,
        String bizType,
        String bizCategory,
        String bizAddress,
        String openDate
) {}
