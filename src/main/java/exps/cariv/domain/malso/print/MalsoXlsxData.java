package exps.cariv.domain.malso.print;

/**
 * 말소등록신청서(템플릿 기반) 채우기용 데이터.
 *
 * - owner*  : 소유자(성명/등록번호/주소)
 * - vehicle* : 차량번호/차대번호/주행거리
 * - applicant* : 신청인(수임자)
 * - poa*    : 위임장 위임자 정보 + 서명/인 이미지
 */
public record MalsoXlsxData(
        String ownerName,
        String ownerIdNo,
        String ownerAddress,

        String vehicleRegistrationNo,
        String vehicleChassisNo,
        Long vehicleMileage,

        String applicantName,
        String applicantBirthDate,

        String poaName,
        String poaRepresentativeName,
        String poaBizNo,
        String poaAddress,
        byte[] poaSignImageBytes
) {}
