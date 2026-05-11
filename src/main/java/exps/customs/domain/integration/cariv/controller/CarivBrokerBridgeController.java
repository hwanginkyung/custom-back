package exps.customs.domain.integration.cariv.controller;

import exps.customs.domain.integration.cariv.dto.CarivBrokerConnectionRequest;
import exps.customs.domain.integration.cariv.dto.CarivBrokerOptionResponse;
import exps.customs.domain.integration.cariv.service.CarivBrokerBridgeService;
import exps.customs.domain.integration.cariv.service.CarivCaseSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cariv-sync/bridge")
@RequiredArgsConstructor
@Tag(name = "Cariv Bridge", description = "Cariv 서버용 관세사 연동 브리지 API")
public class CarivBrokerBridgeController {

    private final CarivCaseSyncService carivCaseSyncService;
    private final CarivBrokerBridgeService carivBrokerBridgeService;

    @GetMapping("/brokers")
    @Operation(summary = "관세사 업체 목록 + 연동 상태 조회")
    public ResponseEntity<List<CarivBrokerOptionResponse>> listBrokers(
            @RequestHeader(name = "X-Agent-Token", required = false) String agentToken,
            @RequestParam Long exporterCompanyId,
            HttpServletRequest httpRequest
    ) {
        carivCaseSyncService.assertPushAuthorized(httpRequest, agentToken);
        return ResponseEntity.ok(carivBrokerBridgeService.listBrokerOptions(exporterCompanyId));
    }

    @PostMapping("/connection-requests")
    @Operation(summary = "관세사 연동 요청 생성/재요청")
    public ResponseEntity<CarivBrokerOptionResponse> requestConnection(
            @RequestHeader(name = "X-Agent-Token", required = false) String agentToken,
            @Valid @RequestBody CarivBrokerConnectionRequest request,
            HttpServletRequest httpRequest
    ) {
        carivCaseSyncService.assertPushAuthorized(httpRequest, agentToken);
        return ResponseEntity.ok(carivBrokerBridgeService.requestConnection(request));
    }
}
