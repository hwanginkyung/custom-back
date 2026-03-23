package exps.cariv.domain.ocr.controller;

import exps.cariv.domain.ocr.dto.response.OcrJobStatusResponse;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ocr/jobs")
@Tag(name = "OCR Job", description = "OCR 작업 상태 조회 API")
public class OcrJobController {

    private final OcrParseJobRepository repo;
    private final OcrResultNormalizer ocrResultNormalizer;

    @GetMapping("/{jobId}")
    @Operation(
            summary = "OCR 작업 상태 조회",
            description = "jobId 기준으로 OCR 처리 상태, 에러 메시지, 필드 단위 결과를 조회합니다."
    )
    public OcrJobStatusResponse getJob(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        OcrParseJob job = repo.findByCompanyIdAndId(me.getCompanyId(), jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        return new OcrJobStatusResponse(
                job.getId(),
                job.getDocumentType(),
                job.getStatus(),
                job.getVehicleId(),
                job.getVehicleDocumentId(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getErrorMessage(),
                ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson())
        );
    }
}
