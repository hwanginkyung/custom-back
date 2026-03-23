package exps.cariv.domain.vehicle.dto;

import exps.cariv.domain.vehicle.entity.OwnerType;
import exps.cariv.domain.vehicle.entity.TransmissionType;
import java.time.LocalDate;

public record VehiclePatch(
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
        OwnerType ownerType,
        String manufactureYearMonth,
        Integer displacement,
        LocalDate firstRegistrationDate,
        TransmissionType transmission,
        String fuelType,
        String color,
        Integer weight,
        Integer seatingCapacity,
        Integer length,
        Integer height,
        Integer width
) {}
