package exps.customs.domain.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "화주 동기화 결과")
public class ClientSyncResponse {

    @Schema(description = "요청 수신 건수")
    private final int received;

    @Schema(description = "코드 기준 고유 건수")
    private final int distinct;

    @Schema(description = "신규 생성 건수")
    private final int created;

    @Schema(description = "수정 건수")
    private final int updated;

    @Schema(description = "변경 없음 건수")
    private final int unchanged;

    @Schema(description = "스킵 건수")
    private final int skipped;

    @Schema(description = "동기화 소스")
    private final String source;

    @Schema(description = "체크포인트(선택)")
    private final String checkpoint;
}

