package exps.cariv.domain.shipper.controller;

import exps.cariv.domain.shipper.dto.request.ShipperCreateRequest;
import exps.cariv.domain.shipper.dto.request.ShipperPhoneUpdateRequest;
import exps.cariv.domain.shipper.dto.request.BizRegSnapshotUpdateRequest;
import exps.cariv.domain.shipper.dto.request.IdCardSnapshotUpdateRequest;
import exps.cariv.domain.shipper.dto.response.BizRegDocumentResponse;
import exps.cariv.domain.shipper.dto.response.IdCardDocumentResponse;
import exps.cariv.domain.shipper.dto.response.ShipperDetailResponse;
import exps.cariv.domain.shipper.dto.response.ShipperDocumentUploadResponse;
import exps.cariv.domain.shipper.dto.response.ShipperRequiredDocsResponse;
import exps.cariv.domain.shipper.service.ShipperCommandService;
import exps.cariv.domain.shipper.service.ShipperDocumentSnapshotService;
import exps.cariv.domain.shipper.service.ShipperQueryService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/shippers")
@RequiredArgsConstructor
@Tag(name = "Shipper", description = "화주 정보 및 화주 문서 관리 API")
public class ShipperController {

    private final ShipperQueryService queryService;
    private final ShipperCommandService commandService;
    private final ShipperDocumentSnapshotService snapshotService;

    // ───────────────────────────────────────────────
    // 0. 화주 추가 (POST /api/shippers)
    // ───────────────────────────────────────────────
    @PostMapping
    @Operation(
            summary = "화주 추가",
            description = "화주 이름, 화주유형, 연락처를 입력하여 새 화주를 등록합니다."
    )
    public ResponseEntity<Long> createShipper(
            @AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody ShipperCreateRequest req
    ) {
        Long shipperId = commandService.createShipper(
                me.getCompanyId(),
                req.name(),
                req.shipperType(),
                req.phone()
        );
        return ResponseEntity.ok(shipperId);
    }

    // ───────────────────────────────────────────────
    // 1. 화주 관리 페이지 진입 (GET /api/shippers)
    // ───────────────────────────────────────────────
    @GetMapping
    @Operation(
            summary = "화주 목록 조회",
            description = "회사 소속 화주 상세 목록을 조회합니다."
    )
    public ResponseEntity<List<ShipperDetailResponse>> listAll(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(queryService.listAll(me.getCompanyId(), query));
    }

    @GetMapping("/{shipperId}/required-docs")
    @Operation(
            summary = "화주 필수 문서 점검",
            description = "차량 등록 전 화주 유형별 필수 문서(BIZ_REG/CEO_ID/SIGN) 업로드 여부를 조회합니다."
    )
    public ResponseEntity<ShipperRequiredDocsResponse> getRequiredDocsStatus(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long shipperId
    ) {
        return ResponseEntity.ok(queryService.getRequiredDocsStatus(me.getCompanyId(), shipperId));
    }

    // ───────────────────────────────────────────────
    // 2. 문서 업로드 (POST /api/shippers/{shipperId}/document/upload)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/{shipperId}/document/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "화주 문서 업로드",
            description = "화주 문서를 업로드하고 문서 타입별 OCR 작업을 생성합니다."
    )
    public ResponseEntity<ShipperDocumentUploadResponse> uploadDocument(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long shipperId,
            @RequestParam("type") String type,
            @RequestPart("document") MultipartFile file
    ) {
        return ResponseEntity.ok(
                commandService.uploadDocument(me.getCompanyId(), me.getUserId(), shipperId, type, file)
        );
    }

    @GetMapping("/document/biz-reg/{documentId}/snapshot")
    @Operation(
            summary = "사업자등록증 OCR 스냅샷 조회",
            description = "documentId 기준으로 사업자등록증 이미지(S3 key)와 OCR 결과를 조회합니다."
    )
    public ResponseEntity<BizRegDocumentResponse> getBizRegSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(snapshotService.getBizRegSnapshot(me.getCompanyId(), documentId));
    }

    @GetMapping("/document/biz-reg/jobs/{jobId}/snapshot")
    @Operation(
            summary = "사업자등록증 OCR 스냅샷 조회 (jobId)",
            description = "알림 등에서 전달된 jobId 기준으로 사업자등록증 OCR 스냅샷을 조회합니다."
    )
    public ResponseEntity<BizRegDocumentResponse> getBizRegSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(snapshotService.getBizRegSnapshotByJobId(me.getCompanyId(), jobId));
    }

    @PatchMapping("/document/biz-reg/{documentId}/snapshot")
    @Operation(
            summary = "사업자등록증 OCR 스냅샷 수정 저장",
            description = "documentId 기준으로 사업자등록증 OCR 인식 결과를 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateBizRegSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody BizRegSnapshotUpdateRequest req
    ) {
        snapshotService.updateBizRegSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/document/id-card/{documentId}/snapshot")
    @Operation(
            summary = "신분증 OCR 스냅샷 조회",
            description = "documentId 기준으로 신분증 이미지(S3 key)와 OCR 결과를 조회합니다."
    )
    public ResponseEntity<IdCardDocumentResponse> getIdCardSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(snapshotService.getIdCardSnapshot(me.getCompanyId(), documentId));
    }

    @GetMapping("/document/id-card/jobs/{jobId}/snapshot")
    @Operation(
            summary = "신분증 OCR 스냅샷 조회 (jobId)",
            description = "알림 등에서 전달된 jobId 기준으로 신분증 OCR 스냅샷을 조회합니다."
    )
    public ResponseEntity<IdCardDocumentResponse> getIdCardSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(snapshotService.getIdCardSnapshotByJobId(me.getCompanyId(), jobId));
    }

    @PatchMapping("/document/id-card/{documentId}/snapshot")
    @Operation(
            summary = "신분증 OCR 스냅샷 수정 저장",
            description = "documentId 기준으로 신분증 OCR 인식 결과를 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateIdCardSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody IdCardSnapshotUpdateRequest req
    ) {
        snapshotService.updateIdCardSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────
    // 3. 문서 삭제 (DELETE /api/shippers/{shipperId}/document?type=...)
    // ───────────────────────────────────────────────
    @DeleteMapping("/{shipperId}/document")
    @Operation(
            summary = "화주 문서 삭제",
            description = "화주 문서를 타입 기준으로 삭제합니다. (CEO_ID/ID_CARD, BIZ_REG/BIZ_REGISTRATION, SIGN)"
    )
    public ResponseEntity<Void> deleteDocument(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long shipperId,
            @RequestParam("type") String type
    ) {
        commandService.deleteDocument(me.getCompanyId(), shipperId, type);
        return ResponseEntity.noContent().build();
    }

    // ───────────────────────────────────────────────
    // 4. 휴대전화 수정 (PATCH /api/shippers/{shipperId}/phone)
    // ───────────────────────────────────────────────
    @PatchMapping("/{shipperId}/phone")
    @Operation(
            summary = "화주 전화번호 수정",
            description = "화주의 휴대전화 번호를 수정합니다."
    )
    public ResponseEntity<Void> updatePhone(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long shipperId,
            @Valid @RequestBody ShipperPhoneUpdateRequest req
    ) {
        commandService.updatePhone(me.getCompanyId(), shipperId, req.phone());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{shipperId}")
    @Operation(
            summary = "화주 삭제(soft delete)",
            description = "화주를 비활성(active=false) 처리합니다."
    )
    public ResponseEntity<Void> deleteShipper(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long shipperId
    ) {
        commandService.deleteShipper(me.getCompanyId(), shipperId);
        return ResponseEntity.noContent().build();
    }
}
