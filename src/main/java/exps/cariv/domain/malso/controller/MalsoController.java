package exps.cariv.domain.malso.controller;

import exps.cariv.domain.malso.dto.request.MalsoCompleteUpdateRequest;
import exps.cariv.domain.malso.dto.request.MalsoListRequest;
import exps.cariv.domain.malso.dto.response.MalsoCompleteResponse;
import exps.cariv.domain.malso.dto.response.MalsoDetailResponse;
import exps.cariv.domain.malso.dto.response.MalsoListResponse;
import exps.cariv.domain.malso.dto.response.MalsoUploadResponse;
import exps.cariv.domain.malso.service.MalsoCommandService;
import exps.cariv.domain.malso.service.MalsoQueryService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/malso")
@RequiredArgsConstructor
@Tag(name = "Malso", description = "말소 진행/완료 처리 및 말소 문서 API")
public class MalsoController {

    private final MalsoQueryService queryService;
    private final MalsoCommandService commandService;

    // ───────────────────────────────────────────────
    // 1. 말소 목록 (GET /api/malso/status)
    // ───────────────────────────────────────────────
    @GetMapping("/status")
    @Operation(
            summary = "말소 목록 조회",
            description = "목록 필터 조회 API입니다. stage 파라미터는 WAITING | IN_PROGRESS | DONE (또는 하위호환: REGISTERED_BY_DEALER | DEREG_IN_PROGRESS | DEREG_COMPLETED)을 받습니다. 응답의 stage는 차량 워크플로우 단계(BEFORE_DEREGISTRATION | BEFORE_REPORT), 말소 진행 상태는 malsoStatus/malsoStatusLabel에 제공합니다."
    )
    public ResponseEntity<Page<MalsoListResponse>> list(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String shipperName,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        MalsoListRequest req = new MalsoListRequest(stage, shipperName, from, to, page, size);
        return ResponseEntity.ok(queryService.list(me.getCompanyId(), req));
    }

    // ───────────────────────────────────────────────
    // 2. 차량 한대 조회 (GET /api/malso/{vehicleId})
    // ───────────────────────────────────────────────
    @GetMapping("/{vehicleId}")
    @Operation(
            summary = "말소 상세 조회",
            description = "차량 한 건의 말소 상세 정보(매입처 유형)를 조회합니다."
    )
    public ResponseEntity<MalsoDetailResponse> detail(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return ResponseEntity.ok(queryService.detail(me.getCompanyId(), vehicleId));
    }

    // ───────────────────────────────────────────────
    // 3. 출력(인쇄) 관련 조회 API → PrintController (/api/print/malso/{vehicleId}/*)
    //    - GET /api/print/malso/{vehicleId}/items      : 출력 모달 문서 목록
    //    - POST /api/print/malso/{vehicleId}/prepare   : 출력 문서 사전 생성
    //    - GET /api/print/malso/{vehicleId}/{key}.pdf    : 개별 PDF 미리보기
    //    - GET /api/print/malso/{vehicleId}/{key}.xlsx   : 개별 XLSX 다운로드
    //    - GET /api/print/malso/{vehicleId}/bundle.zip   : 전체 ZIP 번들
    // ───────────────────────────────────────────────

    // ───────────────────────────────────────────────
    // 6. 말소증 업로드 (POST /api/malso/upload)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "말소증 업로드",
            description = "말소증 파일을 업로드하고 OCR 작업을 생성합니다. 차량 식별은 OCR 결과(VIN/차량번호)로 자동 매칭됩니다."
    )
    public ResponseEntity<MalsoUploadResponse> uploadDeregistration(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                commandService.uploadDeregistration(me.getCompanyId(), me.getUserId(), file)
        );
    }

    // ───────────────────────────────────────────────
    // 7. 말소 완료 알림 클릭 (GET /api/malso/{vehicleId}/complete)
    // ───────────────────────────────────────────────
    @GetMapping("/{vehicleId}/complete")
    @Operation(
            summary = "말소 완료 상세 조회",
            description = "말소 완료 알림 진입 시 차량의 완료 상세 정보를 조회합니다."
    )
    public ResponseEntity<MalsoCompleteResponse> completeDetail(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return ResponseEntity.ok(queryService.completeDetail(me.getCompanyId(), vehicleId));
    }

    // ───────────────────────────────────────────────
    // 8. 말소 완료 결과 수정 (PATCH /api/malso/{vehicleId}/complete)
    // ───────────────────────────────────────────────
    @PatchMapping("/{vehicleId}/complete")
    @Operation(
            summary = "말소 완료 정보 수정",
            description = "말소 완료 단계에서 필요한 결과 값을 수정합니다. vehicleId 기준으로만 수정합니다."
    )
    public ResponseEntity<Void> updateComplete(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @Valid @RequestBody MalsoCompleteUpdateRequest req
    ) {
        commandService.updateComplete(me.getCompanyId(), vehicleId, req);
        return ResponseEntity.ok().build();
    }
}
