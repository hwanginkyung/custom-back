package exps.cariv.domain.malso.print;

import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 말소 출력 REST API.
 *
 * <h3>엔드포인트 구성</h3>
 * <ul>
 *   <li>GET  /api/print/malso/{vehicleId}/items        - 출력 모달 문서 목록 조회</li>
 *   <li>GET  /api/print/malso/{vehicleId}/{key}.pdf     - 개별 문서 PDF 미리보기</li>
 *   <li>GET  /api/print/malso/{vehicleId}/{key}.xlsx    - 개별 문서 XLSX 다운로드</li>
 *   <li>GET  /api/print/malso/{vehicleId}/bundle.zip    - 전체 문서 ZIP 다운로드</li>
 *   <li>GET  /api/print/malso/{vehicleId}/merged.pdf    - 전체출력용 합본 PDF</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/print/malso")
@RequiredArgsConstructor
@Tag(name = "Malso Print", description = "말소 출력/미리보기/다운로드 API")
public class PrintController {

    private final MalsoPrintService printService;

    // ───────────────────────────────────────────────
    // 1. 출력 모달 문서 목록 조회
    // ───────────────────────────────────────────────
    @GetMapping("/{vehicleId}/items")
    @Operation(
            summary = "말소 출력 모달 문서 목록 조회",
            description = "출력 모달에 노출할 문서 목록을 반환합니다. 기본 문서(말소등록신청서, 가능 시 대표자신분증+사업자등록증(화주))는 내부에서 자동 생성/캐시 후 반환됩니다. 완료 차량은 말소증이 최상단에 추가됩니다."
    )
    public ResponseEntity<MalsoPrintService.PrintItemsResponse> items(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return ResponseEntity.ok(printService.prepareItems(me.getCompanyId(), vehicleId));
    }

    // ───────────────────────────────────────────────
    // 1-1. 출력 문서 사전 생성
    // ───────────────────────────────────────────────
    @PostMapping("/{vehicleId}/prepare")
    @Operation(
            summary = "말소 출력 문서 사전 생성",
            description = "출력 모달의 기본 문서(말소등록신청서, 가능 시 대표자신분증+사업자등록증(화주))를 미리 생성/캐시합니다."
    )
    public ResponseEntity<MalsoPrintService.PrintItemsResponse> prepare(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return ResponseEntity.ok(printService.prepareItems(me.getCompanyId(), vehicleId));
    }

    @PostMapping("/{vehicleId}/prepare-invoice")
    @Operation(
            summary = "Invoice/PackingList 생성",
            description = "금액을 받아 Invoice/PackingList를 생성합니다. 금액이 없으면 랜덤 금액을 적용합니다."
    )
    public ResponseEntity<MalsoPrintService.InvoicePrepareResponse> prepareInvoice(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @RequestBody(required = false) InvoicePrepareRequest req
    ) {
        Long amount = req == null ? null : req.amount();
        return ResponseEntity.ok(printService.prepareInvoice(me.getCompanyId(), vehicleId, amount));
    }

    // ───────────────────────────────────────────────
    // 2. 개별 문서 PDF 미리보기 (inline)
    //    key: deregistration, id_card, owner_id_biz_reg_combined, deregistration_app, invoice
    // ───────────────────────────────────────────────
    @GetMapping(value = "/{vehicleId}/{key}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(
            summary = "말소 문서 PDF 미리보기",
            description = "key에 해당하는 문서를 PDF로 조회합니다. (deregistration, id_card, owner_id_biz_reg_combined, deregistration_app, invoice)"
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
    public ResponseEntity<byte[]> previewPdf(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @PathVariable String key,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        MalsoPrintService.DocumentBytes doc = printService.getDocument(me.getCompanyId(), vehicleId, key);

        if (doc == null || doc.data() == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(doc.filename(), StandardCharsets.UTF_8)
                .build());

        return ResponseEntity.ok().headers(headers).body(doc.data());
    }

    // ───────────────────────────────────────────────
    // 3. 개별 문서 XLSX 다운로드
    //    key: deregistration_app, invoice
    // ───────────────────────────────────────────────
    @GetMapping(value = "/{vehicleId}/{key}.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(
            summary = "말소 문서 XLSX 다운로드",
            description = "key에 해당하는 문서를 XLSX로 다운로드합니다. (deregistration_app, invoice)"
    )
    public ResponseEntity<byte[]> downloadXlsx(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @PathVariable String key
    ) {
        MalsoPrintService.DocumentBytes doc = switch (key) {
            case "deregistration_app" -> printService.getDeregistrationXlsx(me.getCompanyId(), vehicleId);
            case "invoice" -> printService.getInvoiceXlsx(me.getCompanyId(), vehicleId);
            default -> null;
        };

        if (doc == null || doc.data() == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(doc.filename(), StandardCharsets.UTF_8)
                .build());

        return ResponseEntity.ok().headers(headers).body(doc.data());
    }

    // ───────────────────────────────────────────────
    // 4. 전체 문서 ZIP 번들 다운로드
    // ───────────────────────────────────────────────
    @GetMapping(value = "/{vehicleId}/bundle.zip", produces = "application/zip")
    @Operation(
            summary = "말소 문서 ZIP 다운로드",
            description = "차량 기준 말소 문서 전체를 ZIP 번들로 다운로드합니다."
    )
    public ResponseEntity<byte[]> downloadBundle(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId,
            @RequestParam(name = "keys", required = false) List<String> keys
    ) {
        byte[] zip = printService.buildBundle(me.getCompanyId(), vehicleId, keys);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("Deregistration_Document_Set.zip", StandardCharsets.UTF_8)
                .build());

        return ResponseEntity.ok().headers(headers).body(zip);
    }

    // ───────────────────────────────────────────────
    // 5. 전체출력용 합본 PDF (inline)
    //    프론트: 새 탭으로 열어 window.print() 호출
    // ───────────────────────────────────────────────
    @GetMapping(value = "/{vehicleId}/merged.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(
            summary = "말소 합본 PDF 조회",
            description = "전체 출력용 병합 PDF를 생성해 반환하고 출력 이력을 반영합니다."
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
            @PathVariable Long vehicleId,
            @RequestParam(name = "keys", required = false) List<String> keys,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        byte[] pdf = printService.buildMergedPdf(me.getCompanyId(), vehicleId, keys);

        // 전체출력 이력 기록 (대기 → 진행 상태 전환)
        printService.markPrinted(me.getCompanyId(), vehicleId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename("Deregistration_Merged_Print.pdf", StandardCharsets.UTF_8)
                .build());

        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    public record InvoicePrepareRequest(Long amount) {}
}
