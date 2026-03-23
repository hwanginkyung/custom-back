package exps.cariv.domain.registration.dto;

import java.time.LocalDate;

public record RegistrationParsed(
        // ✅ Vehicle과 동일한 이름
        String vin,                // 차대번호
        String vehicleNo,          // 자동차등록번호(번호판)
        String carType,            // 차종
        String vehicleUse,         // 용도
        String modelName,          // 차명
        String engineType,         // 원동기형식
        Long mileageKm,            // (등록증엔 보통 없음, 있으면)
        String ownerName,          // 소유자(성명/명칭)
        String ownerId,            // 생년월일/법인등록번호
        Integer modelYear,
        String fuelType,           // 연료
        String manufactureYearMonth, // 제작연월(yyyy-MM)
        Integer displacement,      // 배기량
        LocalDate firstRegistratedAt, // 최초등록일(있으면)

        // ✅ Vehicle에 없는 등록증 전용
        String modelCode,          // 형식/제작연월 등
        String addressText,        // 사용본거지

        // 제원(등록증에 있으면)
        Integer lengthMm,
        Integer widthMm,
        Integer heightMm,
        Integer weightKg,
        Integer seating,
        Integer maxLoadKg,
        Integer powerKw
) {}
