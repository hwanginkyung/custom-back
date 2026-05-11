package exps.customs.domain.brokercase.controller;

import exps.customs.domain.brokercase.dto.*;
import exps.customs.domain.brokercase.entity.CaseStatus;
import exps.customs.domain.brokercase.service.CaseAttachmentFileService;
import exps.customs.domain.brokercase.service.CaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/cases")
@Tag(name = "Case", description = "케이스 관리 API")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;
    private final CaseAttachmentFileService attachmentFileService;

    @GetMapping
    @Operation(summary = "케이스 목록 조회")
    public ResponseEntity<List<CaseResponse>> getAll() {
        return ResponseEntity.ok(caseService.getAll());
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "상태별 케이스 조회")
    public ResponseEntity<List<CaseResponse>> getByStatus(@PathVariable CaseStatus status) {
        return ResponseEntity.ok(caseService.getByStatus(status));
    }

    @GetMapping("/client/{clientId}")
    @Operation(summary = "화주별 케이스 조회")
    public ResponseEntity<List<CaseResponse>> getByClient(@PathVariable Long clientId) {
        return ResponseEntity.ok(caseService.getByClient(clientId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "케이스 상세 조회")
    public ResponseEntity<CaseResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(caseService.getById(id));
    }

    @GetMapping("/{id}/attachments")
    @Operation(summary = "케이스 첨부파일 목록 조회")
    public ResponseEntity<List<CaseAttachmentResponse>> getAttachments(@PathVariable Long id) {
        return ResponseEntity.ok(caseService.getAttachments(id));
    }

    @GetMapping("/{caseId}/attachments/{attachmentId}/view")
    @Operation(summary = "케이스 첨부파일 미리보기")
    public ResponseEntity<ByteArrayResource> viewAttachment(
            @PathVariable Long caseId,
            @PathVariable Long attachmentId
    ) {
        CaseAttachmentFileResponse file = attachmentFileService.loadAttachment(caseId, attachmentId);
        return fileResponse(file, true);
    }

    @GetMapping("/{caseId}/attachments/{attachmentId}/download")
    @Operation(summary = "케이스 첨부파일 다운로드")
    public ResponseEntity<ByteArrayResource> downloadAttachment(
            @PathVariable Long caseId,
            @PathVariable Long attachmentId
    ) {
        CaseAttachmentFileResponse file = attachmentFileService.loadAttachment(caseId, attachmentId);
        return fileResponse(file, false);
    }

    @PostMapping
    @Operation(summary = "케이스 생성")
    public ResponseEntity<CaseResponse> create(@Valid @RequestBody CreateCaseRequest req) {
        return ResponseEntity.ok(caseService.create(req));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "케이스 수정")
    public ResponseEntity<CaseResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateCaseRequest req) {
        return ResponseEntity.ok(caseService.update(id, req));
    }

    @PostMapping("/{caseId}/cargos")
    @Operation(summary = "화물 추가")
    public ResponseEntity<CargoResponse> addCargo(@PathVariable Long caseId, @Valid @RequestBody CreateCargoRequest req) {
        return ResponseEntity.ok(caseService.addCargo(caseId, req));
    }

    @DeleteMapping("/{caseId}/cargos/{cargoId}")
    @Operation(summary = "화물 삭제")
    public ResponseEntity<String> removeCargo(@PathVariable Long caseId, @PathVariable Long cargoId) {
        caseService.removeCargo(caseId, cargoId);
        return ResponseEntity.ok("ok");
    }

    private ResponseEntity<ByteArrayResource> fileResponse(CaseAttachmentFileResponse file, boolean inline) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.getContentType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.getFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new ByteArrayResource(file.getBytes()));
    }
}
