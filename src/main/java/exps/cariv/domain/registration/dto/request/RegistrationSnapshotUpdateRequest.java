package exps.cariv.domain.registration.dto.request;

import java.time.LocalDate;

/**
 * 등록증 OCR 스냅샷 수동 수정 요청.
 * pre-create 업로드 플로우에서 documentId 기준으로 저장한다.
 */
public record RegistrationSnapshotUpdateRequest(
        String vin,
        String vehicleNo,
        String carType,
        String vehicleUse,
        String modelName,
        String engineType,
        String ownerName,
        String ownerId,
        Integer modelYear,
        String fuelType,
        String manufactureYearMonth,
        Integer displacement,
        LocalDate firstRegistratedAt,
        Long mileageKm,

        String address,
        String modelCode,

        String lengthVal,
        String widthVal,
        String heightVal,
        String weight,
        String seating,
        String maxLoad,
        String power
) {}
