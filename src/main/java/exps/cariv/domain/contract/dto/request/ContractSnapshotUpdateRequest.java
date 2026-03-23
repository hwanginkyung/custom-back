package exps.cariv.domain.contract.dto.request;

/**
 * 매매계약서 OCR 스냅샷 수동 수정 요청.
 */
public record ContractSnapshotUpdateRequest(
        String registrationNo,
        String vehicleType,
        String model,
        String chassisNo
) {}
