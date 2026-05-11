package exps.customs.domain.broker.dto;

import exps.customs.domain.broker.entity.BrokerConnection;
import exps.customs.domain.client.entity.BrokerClient;

import java.time.Instant;

public record ConnectionRequestResponse(
        Long id,
        Long exporterCompanyId,
        String exporterCompanyName,
        String exporterBusinessNumber,
        String status,
        Instant requestedAt,
        Instant approvedAt,
        Long matchedClientId,
        String matchedClientName,
        String matchedBy,
        Long linkedClientId,
        String linkedClientName
) {
    public static ConnectionRequestResponse from(
            BrokerConnection bc,
            BrokerClient matchedClient,
            String matchedBy,
            BrokerClient linkedClient
    ) {
        return new ConnectionRequestResponse(
                bc.getId(),
                bc.getExporterCompanyId(),
                bc.getExporterCompanyName(),
                bc.getExporterBusinessNumber(),
                bc.getStatus().name(),
                bc.getCreatedAt(),
                bc.getApprovedAt(),
                matchedClient == null ? null : matchedClient.getId(),
                matchedClient == null ? null : matchedClient.getCompanyName(),
                matchedBy,
                bc.getLinkedClientId(),
                linkedClient == null ? null : linkedClient.getCompanyName()
        );
    }
}
