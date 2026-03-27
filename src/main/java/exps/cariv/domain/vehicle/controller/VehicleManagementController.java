package exps.cariv.domain.vehicle.controller;

import exps.cariv.domain.vehicle.dto.request.VehicleCreateRequest;
import exps.cariv.domain.vehicle.dto.request.VehicleListRequest;
import exps.cariv.domain.vehicle.dto.request.VehicleUpdateRequest;
import exps.cariv.domain.auction.dto.request.AuctionSnapshotUpdateRequest;
import exps.cariv.domain.auction.dto.response.AuctionDocumentResponse;
import exps.cariv.domain.contract.dto.request.ContractSnapshotUpdateRequest;
import exps.cariv.domain.contract.dto.response.ContractDocumentResponse;
import exps.cariv.domain.contract.service.ContractCommandService;
import exps.cariv.domain.contract.service.ContractQueryService;
import exps.cariv.domain.contract.service.ContractUploadService;
import exps.cariv.domain.registration.dto.request.RegistrationSnapshotUpdateRequest;
import exps.cariv.domain.registration.dto.response.RegistrationDocumentResponse;
import exps.cariv.domain.vehicle.dto.request.OwnerIdCardSnapshotUpdateRequest;
import exps.cariv.domain.vehicle.dto.response.*;
import exps.cariv.domain.vehicle.service.VehicleCommandService;
import exps.cariv.domain.vehicle.service.VehicleDocumentService;
import exps.cariv.domain.vehicle.service.RefundDocumentService;
import exps.cariv.domain.vehicle.service.VehicleExcelService;
import exps.cariv.domain.vehicle.service.VehicleQueryService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/api/vehicle")
@RequiredArgsConstructor
@Tag(name = "Vehicle", description = "차량 관리 및 차량 문서 업로드 API")
public class VehicleManagementController {

    private final VehicleCommandService commandService;
    private final VehicleQueryService queryService;
    private final VehicleDocumentService documentService;
    private final VehicleExcelService excelService;
    private final RefundDocumentService refundDocumentService;
    private final ContractUploadService contractUploadService;
    private final ContractQueryService contractQueryService;
    private final ContractCommandService contractCommandService;

    // ───────────────────────────────────────────────
    // 1. 차량 목록 (GET /api/vehicle/management)
    // ───────────────────────────────────────────────
    @GetMapping("/management")
    @Operation(
            summary = "차량 목록 조회",
            description = "단계/키워드/화주/수출국/기간/페이지 조건으로 차량 목록을 조회합니다."
    )
    public ResponseEntity<Page<VehicleListResponse>> list(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipperName,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(required = false) String purchaseMonth,  // yyyy-MM (매입일 기준 필터)
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        // purchaseMonth가 지정되면 매입일 기준 날짜 범위 설정 (다중 월 콤마 지원)
        LocalDate purchaseFrom = null;
        LocalDate purchaseTo = null;
        if (purchaseMonth != null && !purchaseMonth.isBlank()) {
            String[] months = purchaseMonth.split(",");
            for (String m : months) {
                YearMonth ym = YearMonth.parse(m.trim(), DateTimeFormatter.ofPattern("yyyy-MM"));
                LocalDate mFrom = ym.atDay(1);
                LocalDate mTo = ym.atEndOfMonth();
                if (purchaseFrom == null || mFrom.isBefore(purchaseFrom)) purchaseFrom = mFrom;
                if (purchaseTo == null || mTo.isAfter(purchaseTo)) purchaseTo = mTo;
            }
        }

        VehicleListRequest req = new VehicleListRequest(
                stage, keyword, shipperName,
                from, to, purchaseFrom, purchaseTo,
                page, size
        );
        return ResponseEntity.ok(queryService.list(me.getCompanyId(), req));
    }

    // ───────────────────────────────────────────────
    // 1-1. 차량 목록 엑셀 내보내기 (GET /api/vehicle/management/excel)
    // ───────────────────────────────────────────────
    @GetMapping("/management/excel")
    @Operation(
            summary = "차량 목록 엑셀 내보내기",
            description = "월별 필터를 적용하여 차량 목록을 엑셀 파일로 다운로드합니다."
    )
    public ResponseEntity<byte[]> exportExcel(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String month  // yyyy-MM 형식
    ) throws IOException {
        YearMonth yearMonth = null;
        if (month != null && !month.isBlank()) {
            yearMonth = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyy-MM"));
        }

        byte[] excelBytes = excelService.exportToExcel(me.getCompanyId(), yearMonth);

        String fileName = "차량현황";
        if (yearMonth != null) {
            fileName += "_" + yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        fileName += ".xlsx";
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .header("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(excelBytes);
    }

    // ───────────────────────────────────────────────
    // 1-1-1. 차량 목록 엑셀 미리보기 (GET /api/vehicle/management/excel/preview)
    // ───────────────────────────────────────────────
    @GetMapping("/management/excel/preview")
    @Operation(
            summary = "차량 목록 엑셀 미리보기 (JSON)",
            description = "엑셀과 동일한 데이터를 JSON 배열로 반환하여 미리보기에 사용합니다."
    )
    public ResponseEntity<java.util.List<VehicleListResponse>> previewExcel(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        LocalDate dateFrom = (from != null && !from.isBlank()) ? LocalDate.parse(from) : null;
        LocalDate dateTo = (to != null && !to.isBlank()) ? LocalDate.parse(to) : null;

        VehicleListRequest req = new VehicleListRequest(
                null, null, null,
                dateFrom, dateTo, null, null,
                0, 10000
        );
        Page<VehicleListResponse> page = queryService.list(me.getCompanyId(), req);
        return ResponseEntity.ok(page.getContent());
    }

    // ───────────────────────────────────────────────
    // 1-2. 환급용 엑셀 내보내기 (GET /api/vehicle/management/refund-excel)
    // ───────────────────────────────────────────────
    @GetMapping("/management/refund-excel")
    @Operation(
            summary = "환급용 엑셀 내보내기",
            description = "월별(매입일 기준) 개인/그밖의사업자 시트 분리 엑셀을 다운로드합니다. months 파라미터로 여러 월 지정 가능."
    )
    public ResponseEntity<byte[]> exportRefundExcel(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String month,     // 단일 yyyy-MM (하위호환)
            @RequestParam(required = false) String months,    // 다중 yyyy-MM,yyyy-MM,...
            @RequestParam String refundType                   // "individual" 또는 "others" (하위호환: "unapplied"/"applied")
    ) throws IOException {
        // 다중 월 파싱
        List<YearMonth> yearMonths = new java.util.ArrayList<>();
        String monthsParam = (months != null && !months.isBlank()) ? months : month;
        if (monthsParam != null && !monthsParam.isBlank()) {
            for (String m : monthsParam.split(",")) {
                yearMonths.add(YearMonth.parse(m.trim(), DateTimeFormatter.ofPattern("yyyy-MM")));
            }
        }
        if (yearMonths.isEmpty()) {
            yearMonths.add(YearMonth.now());
        }

        // refundType 매핑: 하위호환 + 새 방식
        boolean appliedOnly = "applied".equalsIgnoreCase(refundType) || "others".equalsIgnoreCase(refundType);

        byte[] excelBytes = excelService.exportRefundExcel(me.getCompanyId(), yearMonths, refundType);

        String label = "individual".equalsIgnoreCase(refundType) ? "개인" : "그밖의사업자";
        String monthLabel = yearMonths.size() == 1
                ? yearMonths.get(0).format(DateTimeFormatter.ofPattern("yyyy-MM"))
                : yearMonths.get(0).format(DateTimeFormatter.ofPattern("yyyy-MM")) + "_외" + (yearMonths.size() - 1) + "개월";
        String fileName = "환급_" + label + "_" + monthLabel + ".xlsx";
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .header("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(excelBytes);
    }

    // ───────────────────────────────────────────────
    // 1-2-1. 환급 엑셀 미리보기 (GET /api/vehicle/management/refund-excel/preview)
    // ───────────────────────────────────────────────
    @GetMapping("/management/refund-excel/preview")
    @Operation(
            summary = "환급 엑셀 미리보기 (JSON)",
            description = "환급 엑셀과 동일한 데이터를 JSON 배열로 반환하여 미리보기에 사용합니다."
    )
    public ResponseEntity<java.util.List<exps.cariv.domain.vehicle.dto.response.RefundExcelPreviewRow>> previewRefundExcel(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String months,
            @RequestParam String refundType
    ) {
        List<YearMonth> yearMonths = new java.util.ArrayList<>();
        if (months != null && !months.isBlank()) {
            for (String m : months.split(",")) {
                yearMonths.add(YearMonth.parse(m.trim(), DateTimeFormatter.ofPattern("yyyy-MM")));
            }
        }
        if (yearMonths.isEmpty()) {
            yearMonths.add(YearMonth.now());
        }

        return ResponseEntity.ok(
                excelService.previewRefundExcel(me.getCompanyId(), yearMonths, refundType)
        );
    }

    // ───────────────────────────────────────────────
    // 1-3. 환급 관련서류 ZIP 다운로드 (GET /api/vehicle/management/refund-docs)
    // ───────────────────────────────────────────────
    @GetMapping("/management/refund-docs")
    @Operation(
            summary = "환급 관련서류 ZIP 다운로드",
            description = "월별(매입일 기준) 차량 엑셀 + 말소증 + 매매계약서를 ZIP으로 다운로드합니다."
    )
    public ResponseEntity<byte[]> exportRefundDocs(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam String month  // yyyy-MM 형식
    ) throws IOException {
        YearMonth yearMonth = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyy-MM"));
        byte[] zipBytes = refundDocumentService.generateRefundDocsZip(me.getCompanyId(), yearMonth);

        String fileName = "환급_관련서류_" + yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM")) + ".zip";
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .header("Content-Type", "application/zip")
                .body(zipBytes);
    }

    // ───────────────────────────────────────────────
    // 2. 자동차등록증 업로드 → OCR (POST /api/vehicle/upload/reg)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/upload/reg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "등록증 업로드",
            description = "자동차등록증 파일을 업로드하고 OCR 작업을 생성합니다."
    )
    public ResponseEntity<DocumentUploadResponse> uploadRegistration(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                documentService.uploadRegistration(me.getCompanyId(), me.getUserId(), file)
        );
    }

    // ───────────────────────────────────────────────
    // 2-0. 차주 신분증 업로드 (POST /api/vehicle/upload/id-card)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/upload/id-card", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "차주 신분증 업로드",
            description = "차주 신분증 파일을 업로드하고 OCR 작업을 생성합니다."
    )
    public ResponseEntity<DocumentUploadResponse> uploadOwnerIdCard(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                documentService.uploadOwnerIdCard(me.getCompanyId(), me.getUserId(), file)
        );
    }

    // ───────────────────────────────────────────────
    // 2-0-1. 신분증 OCR 스냅샷 조회 (GET /api/vehicle/upload/id-card/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @GetMapping("/upload/id-card/{documentId}/snapshot")
    @Operation(
            summary = "신분증 OCR 스냅샷 조회",
            description = "documentId 기준으로 신분증 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<OwnerIdCardDocumentResponse> getOwnerIdCardSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(
                documentService.getOwnerIdCardSnapshot(me.getCompanyId(), documentId)
        );
    }

    @GetMapping("/upload/id-card/jobs/{jobId}/snapshot")
    @Operation(
            summary = "신분증 OCR 스냅샷 조회 (jobId)",
            description = "jobId 기준으로 신분증 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<OwnerIdCardDocumentResponse> getOwnerIdCardSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(
                documentService.getOwnerIdCardSnapshotByJobId(me.getCompanyId(), jobId)
        );
    }

    // ───────────────────────────────────────────────
    // 2-0-2. 신분증 OCR 스냅샷 수정 저장 (PATCH /api/vehicle/upload/id-card/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @PatchMapping("/upload/id-card/{documentId}/snapshot")
    @Operation(
            summary = "신분증 OCR 스냅샷 수정 저장",
            description = "신분증 OCR 인식 결과를 documentId 기준으로 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateOwnerIdCardSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody OwnerIdCardSnapshotUpdateRequest req
    ) {
        documentService.updateOwnerIdCardSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────
    // 2-1. 등록증 OCR 스냅샷 수동 수정 저장 (PATCH /api/vehicle/upload/reg/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @GetMapping("/upload/reg/{documentId}/snapshot")
    @Operation(
            summary = "등록증 OCR 스냅샷 조회",
            description = "차량 생성 전/후 상관없이 documentId 기준으로 등록증 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<RegistrationDocumentResponse> getRegistrationSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(
                documentService.getRegistrationSnapshot(me.getCompanyId(), documentId)
        );
    }

    @GetMapping("/upload/reg/jobs/{jobId}/snapshot")
    @Operation(
            summary = "등록증 OCR 스냅샷 조회 (jobId)",
            description = "알림 등에서 전달된 jobId 기준으로 등록증 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<RegistrationDocumentResponse> getRegistrationSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(
                documentService.getRegistrationSnapshotByJobId(me.getCompanyId(), jobId)
        );
    }

    // ───────────────────────────────────────────────
    // 2-2. 등록증 OCR 스냅샷 수동 수정 저장 (PATCH /api/vehicle/upload/reg/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @PatchMapping("/upload/reg/{documentId}/snapshot")
    @Operation(
            summary = "등록증 OCR 스냅샷 수정 저장",
            description = "차량 생성 전 등록증 OCR 인식 결과를 documentId 기준으로 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateRegistrationSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody RegistrationSnapshotUpdateRequest req
    ) {
        documentService.updateRegistrationSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────
    // 3. 경락사실확인서 업로드 (POST /api/vehicle/upload/auction)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/upload/auction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "경락사실확인서 업로드",
            description = "경락사실확인서 파일을 업로드하고 OCR 작업을 생성합니다."
    )
    public ResponseEntity<DocumentUploadResponse> uploadAuction(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                documentService.uploadAuctionCertificate(me.getCompanyId(), me.getUserId(), file)
        );
    }

    // ───────────────────────────────────────────────
    // 3-1. 경락사실확인서 OCR 스냅샷 조회 (GET /api/vehicle/upload/auction/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @GetMapping("/upload/auction/{documentId}/snapshot")
    @Operation(
            summary = "경락사실확인서 OCR 스냅샷 조회",
            description = "차량 생성 전/후 상관없이 documentId 기준으로 경락사실확인서 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<AuctionDocumentResponse> getAuctionSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(
                documentService.getAuctionSnapshot(me.getCompanyId(), documentId)
        );
    }

    @GetMapping("/upload/auction/jobs/{jobId}/snapshot")
    @Operation(
            summary = "경락사실확인서 OCR 스냅샷 조회 (jobId)",
            description = "알림 등에서 전달된 jobId 기준으로 경락사실확인서 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<AuctionDocumentResponse> getAuctionSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(
                documentService.getAuctionSnapshotByJobId(me.getCompanyId(), jobId)
        );
    }

    // ───────────────────────────────────────────────
    // 3-2. 경락사실확인서 OCR 스냅샷 수동 수정 저장 (PATCH /api/vehicle/upload/auction/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @PatchMapping("/upload/auction/{documentId}/snapshot")
    @Operation(
            summary = "경락사실확인서 OCR 스냅샷 수정 저장",
            description = "차량 생성 전 경락사실확인서 OCR 인식 결과를 documentId 기준으로 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateAuctionSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody AuctionSnapshotUpdateRequest req
    ) {
        documentService.updateAuctionSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────
    // 4. 매매계약서 업로드 (POST /api/vehicle/upload/contract)
    // ───────────────────────────────────────────────
    @PostMapping(value = "/upload/contract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "매매계약서 업로드",
            description = "매매계약서 파일을 업로드하고 OCR 작업을 생성합니다."
    )
    public ResponseEntity<DocumentUploadResponse> uploadContract(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestPart("file") MultipartFile file
    ) {
        var result = contractUploadService.uploadAndEnqueue(
                me.getCompanyId(), me.getUserId(), 0L, file);
        return ResponseEntity.ok(
                new DocumentUploadResponse(result.contractDocumentId(), result.s3Key(), result.jobId())
        );
    }

    // ───────────────────────────────────────────────
    // 4-1. 매매계약서 OCR 스냅샷 조회 (GET /api/vehicle/upload/contract/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @GetMapping("/upload/contract/{documentId}/snapshot")
    @Operation(
            summary = "매매계약서 OCR 스냅샷 조회",
            description = "차량 생성 전/후 상관없이 documentId 기준으로 매매계약서 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<ContractDocumentResponse> getContractSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(
                contractQueryService.getSnapshot(me.getCompanyId(), documentId)
        );
    }

    @GetMapping("/upload/contract/jobs/{jobId}/snapshot")
    @Operation(
            summary = "매매계약서 OCR 스냅샷 조회 (jobId)",
            description = "알림 등에서 전달된 jobId 기준으로 매매계약서 OCR 스냅샷과 인식 결과를 조회합니다."
    )
    public ResponseEntity<ContractDocumentResponse> getContractSnapshotByJobId(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long jobId
    ) {
        return ResponseEntity.ok(
                contractQueryService.getSnapshotByJobId(me.getCompanyId(), jobId)
        );
    }

    // ───────────────────────────────────────────────
    // 4-2. 매매계약서 OCR 스냅샷 수동 수정 저장 (PATCH /api/vehicle/upload/contract/{documentId}/snapshot)
    // ───────────────────────────────────────────────
    @PatchMapping("/upload/contract/{documentId}/snapshot")
    @Operation(
            summary = "매매계약서 OCR 스냅샷 수정 저장",
            description = "차량 생성 전 매매계약서 OCR 인식 결과를 documentId 기준으로 수동 수정 저장합니다."
    )
    public ResponseEntity<Void> updateContractSnapshot(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long documentId,
            @Valid @RequestBody ContractSnapshotUpdateRequest req
    ) {
        contractCommandService.updateSnapshot(me.getCompanyId(), documentId, req);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────
    // 5. 차량 등록 (POST /api/vehicle/upload)
    // ───────────────────────────────────────────────
    @PostMapping("/upload")
    @Operation(
            summary = "차량 등록",
            description = "OCR 결과와 입력값을 기반으로 차량을 등록합니다."
    )
    public ResponseEntity<VehicleCreateResponse> createVehicle(
            @AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody VehicleCreateRequest req
    ) {
        return ResponseEntity.ok(
                commandService.createVehicle(me.getCompanyId(), req)
        );
    }

    // ───────────────────────────────────────────────
    // 6. 차량 상세 (GET /api/vehicle/detail/{vehicleId})
    // ───────────────────────────────────────────────
    @GetMapping("/detail/{vehicleId}")
    @Operation(
            summary = "차량 상세 조회",
            description = "차량 기본 정보와 화면 표시용 상세 데이터를 조회합니다."
    )
    public ResponseEntity<VehicleDetailResponse> detail(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return ResponseEntity.ok(
                queryService.detail(me.getCompanyId(), vehicleId)
        );
    }

    // ───────────────────────────────────────────────
    // 7. 차량 수정 (PATCH /api/vehicle/detail/{vehicleId})
    // ───────────────────────────────────────────────
    @PatchMapping("/detail/{vehicleId}")
    @Operation(
            summary = "차량 정보 수정",
            description = "차량 상세 정보를 수정합니다."
    )
    public ResponseEntity<Void> update(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @Valid @RequestBody VehicleUpdateRequest req
    ) {
        commandService.updateVehicle(me.getCompanyId(), vehicleId, req);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────
    // 8. 차량 삭제 (DELETE /api/vehicle/detail/{vehicleId})
    // ───────────────────────────────────────────────
    @DeleteMapping("/detail/{vehicleId}")
    @Operation(
            summary = "차량 삭제",
            description = "차량과 연관된 관리 데이터를 삭제합니다."
    )
    public ResponseEntity<VehicleDeleteResponse> delete(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        commandService.deleteVehicle(me.getCompanyId(), vehicleId);
        return ResponseEntity.ok(
                new VehicleDeleteResponse(vehicleId, true, "차량이 삭제되었습니다.")
        );
    }

    // ───────────────────────────────────────────────
    // 9. 첨부파일 미리보기 (GET /api/vehicle/detail/{vehicleId}/attachments/{slotKey}/preview)
    // ───────────────────────────────────────────────
    @GetMapping("/detail/{vehicleId}/attachments/{slotKey}/preview")
    @Operation(
            summary = "첨부파일 미리보기",
            description = "차량 상세의 슬롯 키로 첨부파일을 인라인(미리보기)으로 반환합니다."
    )
    public ResponseEntity<byte[]> previewAttachment(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @PathVariable String slotKey
    ) {
        VehicleQueryService.AttachmentFileData data =
                queryService.loadAttachmentFile(me.getCompanyId(), vehicleId, slotKey);
        String encodedFilename = URLEncoder.encode(data.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE, data.contentType())
                .body(data.bytes());
    }

    // ───────────────────────────────────────────────
    // 10. 첨부파일 다운로드 (GET /api/vehicle/detail/{vehicleId}/attachments/{slotKey}/download)
    // ───────────────────────────────────────────────
    @GetMapping("/detail/{vehicleId}/attachments/{slotKey}/download")
    @Operation(
            summary = "첨부파일 다운로드",
            description = "차량 상세의 슬롯 키로 첨부파일을 다운로드(attachment)로 반환합니다."
    )
    public ResponseEntity<byte[]> downloadAttachment(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @PathVariable String slotKey
    ) {
        VehicleQueryService.AttachmentFileData data =
                queryService.loadAttachmentFile(me.getCompanyId(), vehicleId, slotKey);
        String encodedFilename = URLEncoder.encode(data.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE, data.contentType())
                .body(data.bytes());
    }
}
