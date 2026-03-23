package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "NCustoms temporary save result with line/container")
public class NcustomsContainerTempSaveResponse {
    private final String expoKey;
    private final String expoJechlNo;
    private final String lanNo;
    private final String hangNo;
    private final String containerNo;
    private final String addDtTime;
}

