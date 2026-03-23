package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "NCustoms expo1 create result")
public class NcustomsExportResponse {
    private final String expoKey;
    private final String expoJechlNo;
    private final String pnoExpo;
    private final String dnoExpo;
    private final String addDtTime;
}

