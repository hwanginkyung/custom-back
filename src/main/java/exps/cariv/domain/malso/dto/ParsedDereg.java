package exps.cariv.domain.malso.dto;

import java.time.LocalDate;

public record ParsedDereg(
        String vin,              // 차량 찾기용
        String vehicleNo,         // 선택
        String documentNo,
        String specNo,
        String registrationNo,
        LocalDate deRegistrationDate,
        String deRegistrationReason,
        String rightsRelation,

        // Vehicle에 반영할 값들(원하면)
        String modelName,
        Integer modelYear,
        String ownerName,
        String ownerId,
        Long mileageKm
) {}

