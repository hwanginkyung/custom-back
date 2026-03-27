package exps.cariv.domain.vehicle.dto.request;

import java.time.LocalDate;

/**
 * PATCH /api/vehicle/detail/{vehicleId} — 차량 수정 요청.
 * null 필드는 기존 값 유지.
 */
public record VehicleUpdateRequest(
        // 차량 기본정보
        String vin,
        String vehicleNo,
        String carType,
        String vehicleUse,
        String modelName,
        String engineType,
        Long mileageKm,
        String ownerName,
        String ownerId,
        Integer modelYear,
        String ownerType,
        String manufactureYearMonth,
        Integer displacement,
        LocalDate firstRegistrationDate,
        String transmission,
        String fuelType,
        String color,
        Integer weight,
        Integer seatingCapacity,
        Integer length,
        Integer height,
        Integer width,

        // 화주
        String shipperName,
        Long shipperId,

        // 매입정보
        Long purchasePrice,
        LocalDate purchaseDate,
        LocalDate licenseDate,

        // 매출정보
        Long saleAmount,
        LocalDate saleDate,

        // 상태
        String stage
) {}
