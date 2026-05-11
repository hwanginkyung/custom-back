package exps.customs.domain.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "화주 동기화 모드 설정")
public class ClientSyncConfigResponse {

    @Schema(description = "서버에서 NCustoms DB Pull 동기화 가능 여부")
    private final boolean ncustomsPullEnabled;

    @Schema(description = "로컬 에이전트 Push 동기화 토큰 설정 여부")
    private final boolean agentPushEnabled;

    @Schema(description = "권장 동기화 모드 (PULL | AGENT_PUSH)")
    private final String recommendedMode;
}
