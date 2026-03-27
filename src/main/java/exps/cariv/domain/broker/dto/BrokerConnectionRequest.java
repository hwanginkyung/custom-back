package exps.cariv.domain.broker.dto;

import jakarta.validation.constraints.NotNull;

public record BrokerConnectionRequest(
        @NotNull Long brokerCompanyId
) {}
