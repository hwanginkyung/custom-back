package exps.cariv.domain.shipper.dto.response;

import java.util.List;

public record ShipperRequiredDocsResponse(
        Long shipperId,
        String shipperType,
        boolean ready,
        List<String> requiredTypes,
        List<String> missingTypes
) {}
