package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "NCustoms temp-save claimed job batch response")
public record NcustomsTempSaveJobClaimResponse(
        List<NcustomsTempSaveJobClaimItemResponse> jobs
) {
}
