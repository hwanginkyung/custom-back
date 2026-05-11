package exps.customs.domain.integration.cariv.controller;

import exps.customs.domain.integration.cariv.dto.CarivSyncCaseRequest;
import exps.customs.domain.integration.cariv.dto.CarivSyncCaseResponse;
import exps.customs.domain.integration.cariv.service.CarivCaseSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cariv-sync/push")
@RequiredArgsConstructor
@Tag(name = "Cariv Push", description = "Cariv 서버 -> Customs 서버 push API")
public class CarivCasePushController {

    private final CarivCaseSyncService carivCaseSyncService;

    @PostMapping("/cases")
    @Operation(summary = "Cariv 서버 푸시 케이스 동기화", description = "Cariv 서버가 관세사 케이스를 push로 동기화합니다. X-Agent-Token 헤더가 필요합니다.")
    public ResponseEntity<CarivSyncCaseResponse> pushCase(
            @RequestHeader(name = "X-Agent-Token", required = false) String agentToken,
            @Valid @RequestBody CarivSyncCaseRequest req,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(carivCaseSyncService.syncCaseFromPush(req, agentToken, httpRequest));
    }
}
