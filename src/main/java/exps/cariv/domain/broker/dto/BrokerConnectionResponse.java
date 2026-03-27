package exps.cariv.domain.broker.dto;

import exps.cariv.domain.broker.entity.BrokerConnection;

import java.time.Instant;

public record BrokerConnectionResponse(
        Long id,
        Long brokerCompanyId,
        String brokerCompanyName,
        String status,           // PENDING, APPROVED, REJECTED
        Instant requestedAt,
        Instant approvedAt
) {
    public static BrokerConnectionResponse from(BrokerConnection bc) {
        return new BrokerConnectionResponse(
                bc.getId(),
                bc.getBrokerCompanyId(),
                bc.getBrokerCompanyName(),
                bc.getStatus().name(),
                bc.getCreatedAt(),
                bc.getApprovedAt()
        );
    }
}
