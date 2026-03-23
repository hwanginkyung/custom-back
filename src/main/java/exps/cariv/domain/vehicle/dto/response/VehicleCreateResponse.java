package exps.cariv.domain.vehicle.dto.response;

import java.util.List;

/**
 * POST /api/vehicle/upload 응답.
 */
public record VehicleCreateResponse(
        Long vehicleId,
        String stage,
        boolean reviewNeeded,
        List<String> reviewFields,
        String reviewMessage
) {}
