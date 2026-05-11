package exps.customs.domain.integration.cariv.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CarivDummySeedResponse {
    private final Long pendingConnectionId;
    private final long pendingConnectionCount;
    private final List<CarivSyncCaseResponse> syncedCases;
}
