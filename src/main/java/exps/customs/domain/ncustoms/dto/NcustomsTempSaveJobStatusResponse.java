package exps.customs.domain.ncustoms.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "NCustoms temp-save job status response")
public record NcustomsTempSaveJobStatusResponse(
        Long jobId,
        String status,
        Long caseId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Integer attempts,
        String errorMessage,
        NcustomsContainerTempSaveResponse result
) {
}
