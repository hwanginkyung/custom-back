package exps.cariv.domain.vehicle.dto.response;

public record VehicleDeleteResponse(
        Long vehicleId,
        boolean deleted,
        String message
) {}
