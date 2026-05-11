package exps.customs.domain.integration.cariv.dto;

import exps.customs.domain.broker.entity.ConnectionStatus;
import exps.customs.domain.brokercase.entity.CaseStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CarivSyncCaseResponse {
    private final Long brokerConnectionId;
    private final ConnectionStatus brokerConnectionStatus;
    private final Long clientId;
    private final String clientCompanyName;
    private final String clientExternalCode;
    private final Long caseId;
    private final String caseNumber;
    private final CaseStatus caseStatus;
    private final boolean created;
}
