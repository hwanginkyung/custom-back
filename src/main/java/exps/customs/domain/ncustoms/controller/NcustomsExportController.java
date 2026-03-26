package exps.customs.domain.ncustoms.controller;

import exps.customs.domain.ncustoms.dto.CreateNcustomsExportRequest;
import exps.customs.domain.ncustoms.dto.CreateNcustomsContainerTempSaveRequest;
import exps.customs.domain.ncustoms.dto.NcustomsContainerTempSaveResponse;
import exps.customs.domain.ncustoms.dto.NcustomsExportResponse;
import exps.customs.domain.ncustoms.dto.NcustomsShipperResponse;
import exps.customs.domain.ncustoms.service.NcustomsExportService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ncustoms")
@Tag(name = "NCustoms", description = "NCustoms integration APIs")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ncustoms.datasource", name = "enabled", havingValue = "true")
public class NcustomsExportController {

    private final NcustomsExportService ncustomsExportService;

    @PostMapping("/exports")
    @Operation(summary = "Create NCustoms export", description = "Generate pno/dno and insert into expo1/expodamdang")
    public ResponseEntity<NcustomsExportResponse> createExport(
            @Valid @RequestBody CreateNcustomsExportRequest request,
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        Long actorUserId = me == null ? null : me.getUserId();
        String actorLoginId = me == null ? null : me.getUsername();
        return ResponseEntity.ok(ncustomsExportService.createExport(request, actorUserId, actorLoginId));
    }

    @PostMapping("/exports/temp-with-container")
    @Operation(summary = "Temp save export with container", description = "Save expo1+expo2+expo3+expcar+excon+expo3_ft")
    public ResponseEntity<NcustomsContainerTempSaveResponse> tempSaveWithContainer(
            @Valid @RequestBody CreateNcustomsContainerTempSaveRequest request,
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        Long actorUserId = me == null ? null : me.getUserId();
        String actorLoginId = me == null ? null : me.getUsername();
        return ResponseEntity.ok(ncustomsExportService.createTempSaveWithContainer(request, actorUserId, actorLoginId));
    }

    @GetMapping("/shippers")
    @Operation(summary = "Load shippers", description = "Load shipper list from DDeal with code prefix and keyword")
    public ResponseEntity<List<NcustomsShipperResponse>> getShippers(
            @RequestParam(defaultValue = "00") String codePrefix,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") Integer limit
    ) {
        return ResponseEntity.ok(ncustomsExportService.getShippers(codePrefix, keyword, limit));
    }
}
