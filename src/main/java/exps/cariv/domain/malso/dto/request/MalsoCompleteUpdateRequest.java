package exps.cariv.domain.malso.dto.request;

import java.time.LocalDate;

/**
 * 말소 완료 결과 수정 요청 (PATCH /api/malso/{vehicleId}/complete).
 */
public record MalsoCompleteUpdateRequest(
        String vehicleNo,
        String vin,
        String modelName,
        Integer modelYear,
        String ownerName,
        String ownerId,
        String documentNo,
        String specNo,
        String registrationNo,
        LocalDate deRegistrationDate,
        String deRegistrationReason,
        String rightsRelation
) {}
