package exps.cariv.domain.malso.dto.response;

import java.util.List;

/**
 * 말소 모달 상세 응답.
 * - 말소 도메인 상세 정보만 제공합니다.
 */
public record MalsoDetailResponse(
        Long vehicleId,
        String ownerType,
        String shipperType,
        List<String> requiredDocuments
) {}
