package exps.customs.domain.ncustoms.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "NCustoms temp-save claimed job item")
public record NcustomsTempSaveJobClaimItemResponse(
        Long jobId,
        Long companyId,
        Long caseId,
        JsonNode tempSaveRequest,
        Instant createdAt,
        Integer attempts
) {
}
