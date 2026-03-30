package exps.cariv.domain.vehicle.dto.request;

/**
 * 차량 소유자 신분증 OCR 결과 수동 수정 요청.
 */
public record OwnerIdCardSnapshotUpdateRequest(
        String holderName,
        String idNumber,
        String idAddress,
        Integer rotateDegrees
) {
}
