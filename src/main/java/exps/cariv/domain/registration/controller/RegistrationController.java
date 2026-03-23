package exps.cariv.domain.registration.controller;

import exps.cariv.domain.registration.dto.response.RegistrationDocumentResponse;
import exps.cariv.domain.registration.dto.response.RegistrationUploadResponse;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.domain.registration.service.RegistrationQueryService;
import exps.cariv.domain.registration.service.RegistrationUploadService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vehicles/{vehicleId}/registration")
@Tag(name = "Registration", description = "차량등록증 업로드 및 조회 API")
public class RegistrationController {

    private final RegistrationUploadService uploadService;
    private final RegistrationQueryService queryService;
    private final OcrParseJobRepository ocrJobRepo;
    private final OcrResultNormalizer ocrResultNormalizer;

    /**
     * 차량등록증 업로드(덮어쓰기) → OCR Job enqueue
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @Operation(
            summary = "차량등록증 업로드",
            description = "차량등록증 파일을 업로드하고 OCR 작업을 큐에 등록합니다."
    )
    public RegistrationUploadResponse uploadRegistration(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @RequestPart("file") MultipartFile file
    ) {
        var r = uploadService.uploadAndEnqueue(me.getCompanyId(), me.getUserId(), vehicleId, file);
        return new RegistrationUploadResponse(r.registrationDocumentId(), r.jobId());
    }

    /**
     * 최신 등록증 문서(스냅샷 포함) 조회
     */
    @GetMapping
    @Operation(
            summary = "최신 차량등록증 조회",
            description = "차량의 최신 등록증 원본 정보, OCR 스냅샷, 필드 검증 결과를 조회합니다."
    )
    public RegistrationDocumentResponse getRegistrationDocument(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        RegistrationDocument d = queryService.getByVehicle(me.getCompanyId(), vehicleId);
        var ocrResult = ocrJobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        me.getCompanyId(), d.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(exps.cariv.domain.ocr.dto.response.OcrFieldResult::empty);
        return new RegistrationDocumentResponse(
                d.getId(),
                d.getS3Key(),
                d.getOriginalFilename(),
                d.getUploadedAt(),
                d.getParsedAt(),
                d.toSnapshot(),
                ocrResult
        );
    }
}
