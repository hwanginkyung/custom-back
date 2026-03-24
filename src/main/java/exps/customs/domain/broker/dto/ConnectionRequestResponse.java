package exps.customs.domain.broker.dto;

import exps.customs.domain.broker.entity.BrokerConnection;

import java.time.Instant;

public record ConnectionRequestResponse(
        Long id,
        Long exporterCompanyId,
        String exporterCompanyName,
        String status,
        Instant requestedAt,
        Instant approvedAt
) {
    public static ConnectionRequestResponse from(BrokerConnection bc) {
        return new ConnectionRequestResponse(
                bc.getId(),
                bc.getExporterCompanyId(),
                bc.getExporterCompanyName(),
                bc.getStatus().name(),
                bc.getCreatedAt(),
                bc.getApprovedAt()
        );
    }
}
