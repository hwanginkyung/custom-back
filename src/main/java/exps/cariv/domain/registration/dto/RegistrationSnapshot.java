package exps.cariv.domain.registration.dto;


import java.time.LocalDate;

public record RegistrationSnapshot(
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
