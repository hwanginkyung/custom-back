package exps.customs.domain.ncustoms.controller;

import exps.customs.domain.ncustoms.dto.CreateNcustomsContainerTempSaveRequest;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobClaimResponse;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobClaimRequest;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobCompleteRequest;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobCreateResponse;
import exps.customs.domain.ncustoms.dto.NcustomsTempSaveJobStatusResponse;
import exps.customs.domain.ncustoms.service.NcustomsTempSaveJobService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ncustoms/temp-save/jobs")
@RequiredArgsConstructor
@Tag(name = "NCustoms Temp Save Job", description = "NCustoms temp-save async job APIs for local agent execution")
public class NcustomsTempSaveJobController {

    private final NcustomsTempSaveJobService tempSaveJobService;

    @PostMapping
    @Operation(summary = "Create temp-save job", description = "Queue temp-save request for local NCustoms agent execution")
    public ResponseEntity<NcustomsTempSaveJobCreateResponse> createJob(
            @Valid @RequestBody CreateNcustomsContainerTempSaveRequest request,
            @RequestParam(required = false) Long caseId,
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(tempSaveJobService.createJob(
                me.getCompanyId(),
                me.getUserId(),
                caseId,
                request
        ));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Get temp-save job status")
    public ResponseEntity<NcustomsTempSaveJobStatusResponse> getJobStatus(
            @PathVariable Long jobId,
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(tempSaveJobService.getJobStatus(me.getCompanyId(), jobId));
    }

    @PostMapping("/claim")
    @Operation(summary = "Claim temp-save jobs (agent)", description = "Local agent claims pending jobs. X-Agent-Token is required.")
    public ResponseEntity<NcustomsTempSaveJobClaimResponse> claimJobs(
            @RequestHeader(name = "X-Agent-Token", required = false) String agentToken,
            @Valid @RequestBody NcustomsTempSaveJobClaimRequest request
    ) {
        return ResponseEntity.ok(tempSaveJobService.claimJobs(request, agentToken));
    }

    @PostMapping("/{jobId}/complete")
    @Operation(summary = "Complete temp-save job (agent)", description = "Local agent reports execution result. X-Agent-Token is required.")
    public ResponseEntity<NcustomsTempSaveJobStatusResponse> completeJob(
            @PathVariable Long jobId,
            @RequestHeader(name = "X-Agent-Token", required = false) String agentToken,
            @Valid @RequestBody NcustomsTempSaveJobCompleteRequest request
    ) {
        return ResponseEntity.ok(tempSaveJobService.completeJob(jobId, request, agentToken));
    }
}
