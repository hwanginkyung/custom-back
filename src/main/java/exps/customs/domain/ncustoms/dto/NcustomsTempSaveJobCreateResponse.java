package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "NCustoms temp-save job create response")
public record NcustomsTempSaveJobCreateResponse(
        Long jobId,
        String status,
        Instant createdAt
) {
}
