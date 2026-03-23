package exps.cariv.domain.vehicle.dto;

/**
 * 차량 소유자 신분증 OCR 스냅샷.
 * 요구 필드 3개(성명, 주민등록번호, 주소)만 노출한다.
 */
public record OwnerIdCardSnapshot(
        String holderName,
        String idNumber,
        String idAddress
) {
}
