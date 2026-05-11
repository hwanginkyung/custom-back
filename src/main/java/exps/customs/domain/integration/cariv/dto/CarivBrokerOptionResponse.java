package exps.customs.domain.integration.cariv.dto;

import exps.customs.domain.broker.entity.ConnectionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@Schema(description = "관세사 업체 목록 + 현재 수출자와의 연동 상태")
public class CarivBrokerOptionResponse {

    @Schema(description = "관세사 회사 ID", example = "1")
    private final Long brokerCompanyId;

    @Schema(description = "관세사 업체명", example = "진솔 관세법인")
    private final String brokerCompanyName;

    @Schema(description = "사업자번호", example = "123-45-67890")
    private final String businessNumber;

    @Schema(description = "연동 레코드 ID (없으면 null)", example = "55")
    private final Long connectionId;

    @Schema(description = "연동 상태 (PENDING/APPROVED/REJECTED/NOT_REQUESTED)")
    private final String connectionStatus;

    @Schema(description = "승인 시각", example = "2026-05-06T06:00:00Z")
    private final Instant approvedAt;

    @Schema(description = "연결된 화주 ID", example = "536")
    private final Long linkedClientId;

    @Schema(description = "연결된 화주명", example = "테스트수출사")
    private final String linkedClientName;

    public static CarivBrokerOptionResponse ofNotRequested(Long brokerCompanyId, String brokerCompanyName, String businessNumber) {
        return CarivBrokerOptionResponse.builder()
                .brokerCompanyId(brokerCompanyId)
                .brokerCompanyName(brokerCompanyName)
                .businessNumber(businessNumber)
                .connectionStatus("NOT_REQUESTED")
                .linkedClientId(null)
                .linkedClientName(null)
                .build();
    }

    public static CarivBrokerOptionResponse ofConnection(
            Long brokerCompanyId,
            String brokerCompanyName,
            String businessNumber,
            Long connectionId,
            ConnectionStatus status,
            Instant approvedAt,
            Long linkedClientId,
            String linkedClientName
    ) {
        return CarivBrokerOptionResponse.builder()
                .brokerCompanyId(brokerCompanyId)
                .brokerCompanyName(brokerCompanyName)
                .businessNumber(businessNumber)
                .connectionId(connectionId)
                .connectionStatus(status == null ? "NOT_REQUESTED" : status.name())
                .approvedAt(approvedAt)
                .linkedClientId(linkedClientId)
                .linkedClientName(linkedClientName)
                .build();
    }
}
