package exps.customs.domain.brokercase.dto;

import exps.customs.domain.brokercase.entity.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@Schema(description = "케이스 응답 DTO")
public class CaseResponse {

    private final Long id;
    private final String caseNumber;
    private final Long clientId;
    private final String clientName;
    private final CaseStatus status;
    private final PaymentStatus paymentStatus;
    private final ShippingMethod shippingMethod;
    private final String blNumber;
    private final LocalDate etaDate;
    private final LocalDate ataDate;
    private final LocalDate customsDate;
    private final LocalDate releaseDate;
    private final String departurePorts;
    private final String arrivalPort;
    private final BigDecimal totalAmount;
    private final BigDecimal dutyAmount;
    private final BigDecimal vatAmount;
    private final BigDecimal brokerageFee;
    private final String memo;
    private final Long assigneeId;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<CargoResponse> cargos;

    public static CaseResponse from(BrokerCase c) {
        return CaseResponse.builder()
                .id(c.getId())
                .caseNumber(c.getCaseNumber())
                .clientId(c.getClient() != null ? c.getClient().getId() : null)
                .clientName(c.getClient() != null ? c.getClient().getCompanyName() : null)
                .status(c.getStatus())
                .paymentStatus(c.getPaymentStatus())
                .shippingMethod(c.getShippingMethod())
                .blNumber(c.getBlNumber())
                .etaDate(c.getEtaDate())
                .ataDate(c.getAtaDate())
                .customsDate(c.getCustomsDate())
                .releaseDate(c.getReleaseDate())
                .departurePorts(c.getDeparturePorts())
                .arrivalPort(c.getArrivalPort())
                .totalAmount(c.getTotalAmount())
                .dutyAmount(c.getDutyAmount())
                .vatAmount(c.getVatAmount())
                .brokerageFee(c.getBrokerageFee())
                .memo(c.getMemo())
                .assigneeId(c.getAssigneeId())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .cargos(c.getCargos() != null ? c.getCargos().stream().map(CargoResponse::from).toList() : List.of())
                .build();
    }
}
