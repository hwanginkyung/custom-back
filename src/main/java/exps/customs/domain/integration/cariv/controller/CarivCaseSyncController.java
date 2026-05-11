package exps.customs.domain.integration.cariv.controller;

import exps.customs.domain.integration.cariv.dto.CarivDummySeedResponse;
import exps.customs.domain.integration.cariv.dto.CarivSyncCaseRequest;
import exps.customs.domain.integration.cariv.dto.CarivSyncCaseResponse;
import exps.customs.domain.integration.cariv.service.CarivCaseSyncService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/cariv-sync")
@RequiredArgsConstructor
@Tag(name = "Cariv Sync", description = "Cariv 연동/더미 시드 API")
public class CarivCaseSyncController {

    private final CarivCaseSyncService carivCaseSyncService;

    @PostMapping("/cases")
    @Operation(summary = "Cariv 케이스 동기화", description = "Cariv 스타일 payload를 관세사 케이스로 동기화합니다.")
    public ResponseEntity<CarivSyncCaseResponse> syncCase(
            @AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody CarivSyncCaseRequest req
    ) {
        return ResponseEntity.ok(carivCaseSyncService.syncCase(me.getCompanyId(), req));
    }

    @PostMapping("/dummy-seed")
    @Operation(summary = "Cariv 더미 데이터 생성", description = "케이스 목록/상세 테스트용 더미 케이스를 생성합니다.")
    public ResponseEntity<CarivDummySeedResponse> seedDummy(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "4") int caseCount
    ) {
        return ResponseEntity.ok(carivCaseSyncService.seedDummyCases(me.getCompanyId(), caseCount));
    }
}
