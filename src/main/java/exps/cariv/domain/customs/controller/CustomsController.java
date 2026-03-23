package exps.cariv.domain.customs.controller;

import exps.cariv.domain.customs.dto.request.CustomsListRequest;
import exps.cariv.domain.customs.dto.request.CustomsSendRequest;
import exps.cariv.domain.customs.dto.response.CustomsDetailResponse;
import exps.cariv.domain.customs.dto.response.CustomsListResponse;
import exps.cariv.domain.customs.dto.response.CustomsMockSendResponse;
import exps.cariv.domain.customs.dto.response.CustomsSendResponse;
import exps.cariv.domain.customs.dto.response.CustomsSendResponse.GeneratedDoc;
import exps.cariv.domain.customs.entity.CustomsRequestStatus;
import exps.cariv.domain.customs.service.CustomsCommandService;
import exps.cariv.domain.customs.service.CustomsDocumentService;
import exps.cariv.domain.customs.service.CustomsQueryService;
import exps.cariv.domain.customs.service.CustomsUploadService;
import exps.cariv.domain.customs.service.CustomsUploadService.AssetUploadResponse;
import exps.cariv.domain.customs.service.CustomsUploadService.UploadResponse;
import exps.cariv.domain.export.dto.request.ExportSnapshotUpdateRequest;
import exps.cariv.domain.export.dto.response.ExportDocumentResponse;
import exps.cariv.domain.export.service.ExportSnapshotService;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customs")
@RequiredArgsConstructor
@Tag(name = "Customs", description = "통관 요청/전송 및 수출신고필증 업로드 API")
public class CustomsController {

    private final CustomsQueryService queryService;
    private final CustomsCommandService commandService;
    private final CustomsDocumentService documentService;
    private final CustomsUploadService uploadService;
    private final ExportSnapshotService exportSnapshotService;
    private static final List<PortOption> KOREA_PORTS = List.of(
            new PortOption("KRPUS", "부산항"),
            new PortOption("KRINC", "인천항"),
            new PortOption("KRKAN", "평택·당진항"),
            new PortOption("KRUSN", "울산항"),
            new PortOption("KRGMP", "군산항"),
            new PortOption("KRMAS", "마산항"),
            new PortOption("KRKPO", "포항항"),
            new PortOption("KRMOK", "목포항"),
            new PortOption("KRJJU", "제주항")
    );

    // ───────────────────────────────────────────────
    // 1. 신고필증 목록 (GET /api/customs)
    // ───────────────────────────────────────────────
    @GetMapping
    @Operation(
            summary = "통관 요청 목록 조회",
            description = "목록 필터 조회 API입니다. stage 파라미터는 WAITING | IN_PROGRESS | DONE 을 받습니다."
    )
    public ResponseEntity<Page<CustomsListResponse>> list(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String shipperName,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @Parameter(hidden = true) @RequestParam(required = false) String status,
            @Parameter(hidden = true) @RequestParam(required = false) LocalDate startDate,
            @Parameter(hidden = true) @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        String effectiveStage = (stage != null && !stage.isBlank()) ? stage : status;
        LocalDate effectiveFrom = from != null ? from : startDate;
        LocalDate effectiveTo = to != null ? to : endDate;

        CustomsListRequest req = new CustomsListRequest(
                effectiveStage, shipperName, query, effectiveFrom, effectiveTo, page, size
        );
        return ResponseEntity.ok(queryService.list(me.getCompanyId(), req));
    }

    @GetMapping("/ports")
    @Operation(
            summary = "수출항 코드 목록 조회",
            description = "대한민국 수출항 드롭다운용 코드 목록을 반환합니다."
    )
    public ResponseEntity<List<PortOption>> listPorts() {
        return ResponseEntity.ok(KOREA_PORTS);
    }

    // ───────────────────────────────────────────────
    // 2. 차량 상세 (GET /api/customs/{vehicleId})
    //    → 프론트가 customsStatus 보고 분기:
    //      WAITING  → 관세사 전송 페이지
    //      IN_PROGRESS → "다시 보내기" 모달
    //      DONE → 수출신고필증 미리보기
    // ───────────────────────────────────────────────
    @GetMapping("/{vehicleId}")
    @Operation(
            summary = "통관 상세 조회",
            description = "차량 기준으로 통관 상세 정보를 조회합니다. 프론트는 customsStatus로 화면 분기합니다."
    )
    public ResponseEntity<CustomsDetailResponse> detail(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return ResponseEntity.ok(queryService.detail(me.getCompanyId(), vehicleId));
    }

    // ───────────────────────────────────────────────
    // 3. 초안 생성 (POST /api/customs/draft)
    //    → requestId 발급
    // ───────────────────────────────────────────────
    @PostMapping("/draft")
    @Operation(
            summary = "통관 요청 초안 생성",
            description = "통관 요청 DRAFT를 최소 단위로 생성하고 requestId를 반환합니다."
    )
    public ResponseEntity<CustomsSendResponse> createDraft(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        Long requestId = commandService.createDraft(me.getCompanyId());
        return ResponseEntity.ok(new CustomsSendResponse(requestId, "DRAFT", List.of()));
    }

    // ───────────────────────────────────────────────
    // 3-1. 미리보기 확정 전송 (POST /api/customs/{requestId}/submit)
    // ───────────────────────────────────────────────
    @PostMapping("/{requestId}/submit")
    @Operation(
            summary = "관세사 전송 확정",
            description = "requestId에 최종 입력 데이터를 반영한 뒤 전송(PROCESSING) 처리합니다."
    )
    public ResponseEntity<CustomsSendResponse> submit(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId,
            @Valid @RequestBody CustomsSendRequest req
    ) {
        commandService.submitDraft(me.getCompanyId(), requestId, req);
        return ResponseEntity.ok(buildSendResponse(me.getCompanyId(), requestId, "IN_PROGRESS"));
    }

    // ───────────────────────────────────────────────
    // 4. 다시 보내기 (PUT /api/customs/{requestId}/resend)
    //    → 진행(IN_PROGRESS) 상태 차량, 기존 요청 수정 후 재전송
    // ───────────────────────────────────────────────
    @PutMapping("/{requestId}/resend")
    @Operation(
            summary = "통관 요청 재전송",
            description = "진행 중인 통관 요청을 수정 후 다시 전송합니다."
    )
    public ResponseEntity<CustomsSendResponse> resend(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId,
            @Valid @RequestBody CustomsSendRequest req
    ) {
        Long updatedId = commandService.resendRequest(me.getCompanyId(), requestId, req);
        return ResponseEntity.ok(buildSendResponse(me.getCompanyId(), updatedId, "IN_PROGRESS"));
    }

    // ───────────────────────────────────────────────
    // 4-1. 임시 관세사 보내기 (POST /api/customs/{requestId}/send)
    //    → 실제 외부 전송 없이 상태만 PROCESSING으로 전환
    // ───────────────────────────────────────────────
    @PostMapping("/{requestId}/send")
    @Operation(
            summary = "관세사 보내기 (임시/Mock)",
            description = "실제 외부 전송 없이 통관 요청 상태만 PROCESSING으로 전환합니다."
    )
    public ResponseEntity<CustomsMockSendResponse> sendMock(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId
    ) {
        CustomsRequestStatus status = commandService.sendMock(me.getCompanyId(), requestId);
        return ResponseEntity.ok(new CustomsMockSendResponse(
                requestId,
                toUiRequestStatus(status),
                "MOCK",
                Instant.now()
        ));
    }

    // ───────────────────────────────────────────────
    // 5. 생성된 문서 목록 조회 (GET /api/customs/{requestId}/docs)
    // ───────────────────────────────────────────────
    @GetMapping("/{requestId}/docs")
    @Operation(
            summary = "통관 문서 목록 조회",
            description = "요청 ID 기준으로 생성 가능한 문서 목록(name/previewUrl/downloadUrl/s3Key/s3Url/sourceS3Key/size/date)을 반환합니다."
    )
    public ResponseEntity<List<GeneratedDoc>> listDocs(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId
    ) {
        return ResponseEntity.ok(buildGeneratedDocs(me.getCompanyId(), requestId));
    }

    // ───────────────────────────────────────────────
    // 6. 생성된 문서 개별 조회 (GET /api/customs/{requestId}/docs/{filename})
    // ───────────────────────────────────────────────
    @GetMapping(value = "/{requestId}/docs/{filename}", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(
            summary = "통관 문서 개별 조회",
            description = "요청 ID 기준으로 생성된 문서를 PDF로 반환합니다. download=true면 첨부 다운로드, false면 인라인 미리보기입니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "PDF 문서",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @ApiResponse(responseCode = "404", description = "문서를 찾을 수 없음")
    })
    public ResponseEntity<byte[]> downloadDoc(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId,
            @PathVariable String filename,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        return renderGeneratedDoc(me.getCompanyId(), requestId, filename, download);
    }

    @GetMapping(value = "/{requestId}/docs/{filename}/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(
            summary = "통관 문서 개별 미리보기",
            description = "요청 ID 기준으로 생성된 문서를 인라인(PDF preview)으로 반환합니다."
    )
    public ResponseEntity<byte[]> previewDoc(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId,
            @PathVariable String filename
    ) {
        return renderGeneratedDoc(me.getCompanyId(), requestId, filename, false);
    }

    @GetMapping(value = "/{requestId}/docs/{filename}/download", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(
            summary = "통관 문서 개별 다운로드",
            description = "요청 ID 기준으로 생성된 문서를 첨부파일로 다운로드합니다."
    )
    public ResponseEntity<byte[]> downloadDocExplicit(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId,
            @PathVariable String filename
    ) {
        return renderGeneratedDoc(me.getCompanyId(), requestId, filename, true);
    }

    // ───────────────────────────────────────────────
    // 7. 전체 문서 병합 PDF (GET /api/customs/{requestId}/merged.pdf)
    // ───────────────────────────────────────────────
    @GetMapping(value = "/{requestId}/merged.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(
            summary = "통관 문서 합본 PDF 조회",
            description = "통관 요청 문서를 하나의 병합 PDF로 생성하여 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "병합 PDF",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")
                    )
            )
    })
    public ResponseEntity<byte[]> mergedPdf(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long requestId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        byte[] pdf = documentService.buildMergedPdf(me.getCompanyId(), requestId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename("customs_merged_" + requestId + ".pdf")
                .build());
        headers.setContentLength(pdf.length);

        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    // ───────────────────────────────────────────────
    // 8. 통관 첨부파일 업로드 (POST /api/customs/assets/upload)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/assets/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "통관 첨부파일 업로드",
            description = """
                    통관 요청 첨부파일을 업로드하고 S3 key를 반환합니다.
                    - requestId: /api/customs/draft 응답의 requestId
                    - category:
                      - VEHICLE: 차량 사진(이 경우 vehicleId 필수)
                      - CONTAINER: 컨테이너 사진
                    """
    )
    public ResponseEntity<AssetUploadResponse> uploadAsset(
            @AuthenticationPrincipal CustomUserDetails me,
            @Parameter(description = "업로드할 파일 (jpg/png/pdf 등)")
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "통관 요청 ID (/api/customs/draft 응답값)", example = "123")
            @RequestParam("requestId") Long requestId,
            @Parameter(
                    description = "업로드 카테고리",
                    schema = @Schema(
                            type = "string",
                            allowableValues = {"VEHICLE", "CONTAINER"},
                            example = "VEHICLE"
                    )
            )
            @RequestParam("category") String category,
            @Parameter(description = "차량 ID (category=VEHICLE일 때 필수, category=CONTAINER일 때 생략)", example = "45")
            @RequestParam(value = "vehicleId", required = false) Long vehicleId
    ) {
        return ResponseEntity.ok(
                uploadService.uploadAsset(me.getCompanyId(), requestId, category, vehicleId, file)
        );
    }

    // ───────────────────────────────────────────────
    // 8-1. 통관 첨부파일 삭제 (DELETE /api/customs/assets)
    // ───────────────────────────────────────────────
    @DeleteMapping("/assets")
    @Operation(
            summary = "통관 첨부파일 삭제",
            description = """
                    업로드된 통관 첨부파일을 삭제합니다.
                    - requestId: 통관 요청 ID
                    - category:
                      - VEHICLE: 차량 사진(이 경우 vehicleId 필수)
                      - CONTAINER: 컨테이너 사진
                    - s3Key: 삭제할 파일의 S3 key
                    """
    )
    public ResponseEntity<Void> deleteAsset(
            @AuthenticationPrincipal CustomUserDetails me,
            @Parameter(description = "통관 요청 ID", example = "123")
            @RequestParam("requestId") Long requestId,
            @Parameter(
                    description = "삭제 카테고리",
                    schema = @Schema(
                            type = "string",
                            allowableValues = {"VEHICLE", "CONTAINER"},
                            example = "VEHICLE"
                    )
            )
            @RequestParam("category") String category,
            @Parameter(description = "차량 ID (category=VEHICLE일 때 필수)", example = "45")
            @RequestParam(value = "vehicleId", required = false) Long vehicleId,
            @Parameter(description = "삭제할 첨부파일의 S3 key", example = "customs-requests/1/123/vehicle-45/uuid-photo.jpg")
            @RequestParam("s3Key") String s3Key
    ) {
        uploadService.deleteAsset(me.getCompanyId(), requestId, category, vehicleId, s3Key);
        return ResponseEntity.noContent().build();
    }

    // ───────────────────────────────────────────────
    // 9. 수출신고필증 업로드 (POST /api/customs/upload)
    //    → S3 업로드 → OCR Job 생성 → 알림
    //    → OCR 완료 시 Vehicle stage → COMPLETED
    // ───────────────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "수출신고필증 업로드",
            description = "수출신고필증 파일을 업로드하고 OCR 작업을 생성합니다. 차량 ID는 받지 않으며 OCR 결과(차대번호)로 차량을 자동 매칭합니다."
    )
    public ResponseEntity<UploadResponse> uploadExportCertificate(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                uploadService.uploadExportCertificate(me.getCompanyId(), me.getUserId(), file)
        );
    }

    @GetMapping("/upload/{documentId}/snapshot")
    @Operation(
            summary = "수출신고필증 OCR 스냅샷 조회",
            description = "documentId 기준으로 수출신고필증 이미지(S3 key)와 OCR 인식 결과를 조회합니다."
    )
    public ResponseEntity<ExportDocumentResponse> getExportSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(
                exportSnapshotService.getSnapshot(me.getCompanyId(), documentId)
        );
    }

    @GetMapping("/upload/jobs/{jobId}/snapshot")
    @Operation(
            summary = "수출신고필증 OCR 스냅샷 조회 (jobId)",
            description = "알림 등에서 전달된 jobId 기준으로 수출신고필증 OCR 스냅샷을 조회합니다."
    )
    public ResponseEntity<ExportDocumentResponse> getExportSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(
                exportSnapshotService.getSnapshotByJobId(me.getCompanyId(), jobId)
        );
    }

    @PatchMapping("/upload/{documentId}/snapshot")
    @Operation(
            summary = "수출신고필증 OCR 스냅샷 수정 저장",
            description = "documentId 기준으로 수출신고필증 OCR 인식 결과를 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateExportSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody ExportSnapshotUpdateRequest req
    ) {
        exportSnapshotService.updateSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    // ─── helper ───

    private CustomsSendResponse buildSendResponse(Long companyId, Long requestId, String status) {
        return new CustomsSendResponse(requestId, status, buildGeneratedDocs(companyId, requestId));
    }

    private String toUiRequestStatus(CustomsRequestStatus status) {
        return switch (status) {
            case DRAFT -> "WAITING";
            case SUBMITTED, PROCESSING -> "IN_PROGRESS";
            case COMPLETED -> "DONE";
        };
    }

    private List<GeneratedDoc> buildGeneratedDocs(Long companyId, Long requestId) {
        Map<String, byte[]> docs;
        try {
            docs = documentService.buildDocumentPackage(companyId, requestId);
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return List.of();
            }
            throw e;
        }

        Instant generatedAt = Instant.now();
        Map<String, String> sourceS3KeyMap = documentService.buildDocumentSourceS3KeyMap(companyId, requestId);

        List<GeneratedDoc> generatedDocs = new ArrayList<>();
        for (String name : docs.keySet()) {
            byte[] content = docs.get(name);
            String baseDocPath = "/api/customs/" + requestId + "/docs/" + name;
            String generatedS3Key = documentService.ensureGeneratedDocOnS3(companyId, requestId, name, content);
            String generatedS3Url = documentService.toPublicS3Url(generatedS3Key);
            generatedDocs.add(new GeneratedDoc(
                    name,
                    baseDocPath + "/preview",
                    baseDocPath + "?download=true",
                    generatedS3Key,
                    generatedS3Url,
                    sourceS3KeyMap.get(name),
                    content == null ? 0L : content.length,
                    generatedAt
            ));
        }
        return generatedDocs;
    }

    private ResponseEntity<byte[]> renderGeneratedDoc(Long companyId, Long requestId, String filename, boolean download) {
        Map<String, byte[]> docs = documentService.buildDocumentPackage(companyId, requestId);
        byte[] pdf = docs.get(filename);

        if (pdf == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(filename)
                .build());
        headers.setContentLength(pdf.length);

        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    public record PortOption(
            String code,
            String name
    ) {
    }
}
