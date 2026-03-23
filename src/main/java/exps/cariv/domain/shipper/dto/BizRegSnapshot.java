package exps.cariv.domain.shipper.dto;

/**
 * 사업자등록증 OCR 스냅샷.
 */
public record BizRegSnapshot(
        String companyName,
        String representativeName,
        String bizNumber,
        String bizType,
        String bizCategory,
        String bizAddress,
        String openDate
) {}
