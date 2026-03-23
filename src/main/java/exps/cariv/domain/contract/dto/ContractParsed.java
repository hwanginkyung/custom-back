package exps.cariv.domain.contract.dto;

/**
 * 매매계약서 OCR 파싱 결과 DTO.
 */
public record ContractParsed(
        String registrationNo,   // 차량번호
        String vehicleType,      // 차종
        String model,            // 차명
        String chassisNo         // 차대번호
) {}
