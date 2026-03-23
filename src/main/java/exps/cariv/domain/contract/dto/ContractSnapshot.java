package exps.cariv.domain.contract.dto;

/**
 * 매매계약서 OCR 스냅샷.
 * 화면 수정/조회에 사용하는 최소 필드만 포함한다.
 */
public record ContractSnapshot(
        String registrationNo,   // 자동차등록번호
        String vehicleType,      // 차종
        String model,            // 차명
        String chassisNo         // 차대번호
) {}
