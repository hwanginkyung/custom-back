package exps.cariv.domain.broker.controller;

import exps.cariv.domain.broker.dto.BrokerCompanyItem;
import exps.cariv.domain.broker.dto.BrokerConnectionRequest;
import exps.cariv.domain.broker.dto.BrokerConnectionResponse;
import exps.cariv.domain.broker.service.BrokerConnectionService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/broker-connection")
@RequiredArgsConstructor
@Tag(name = "Broker Connection", description = "관세사 연동 API (수출자 쪽)")
public class BrokerConnectionController {

    private final BrokerConnectionService service;

    @GetMapping("/available")
    @Operation(summary = "연동 가능한 관세사 목록", description = "시스템에 등록된 관세사 회사 목록을 조회합니다.")
    public ResponseEntity<List<BrokerCompanyItem>> listAvailable() {
        return ResponseEntity.ok(service.listAvailableBrokers());
    }

    @GetMapping
    @Operation(summary = "내 연동 현황", description = "수출자의 관세사 연동 현황을 조회합니다.")
    public ResponseEntity<List<BrokerConnectionResponse>> myConnections(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(service.myConnections(me.getCompanyId()));
    }

    @PostMapping
    @Operation(summary = "연동 요청", description = "관세사에게 연동 요청을 보냅니다.")
    public ResponseEntity<BrokerConnectionResponse> request(
            @AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody BrokerConnectionRequest req
    ) {
        return ResponseEntity.ok(
                service.requestConnection(me.getCompanyId(), req.brokerCompanyId())
        );
    }

    @DeleteMapping("/{connectionId}")
    @Operation(summary = "연동 취소", description = "대기 중인 연동 요청을 취소합니다.")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long connectionId
    ) {
        service.cancelConnection(me.getCompanyId(), connectionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/has-broker")
    @Operation(summary = "관세사 연동 여부 확인", description = "승인된 관세사가 있는지 확인합니다.")
    public ResponseEntity<Map<String, Boolean>> hasBroker(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        boolean has = service.hasApprovedBroker(me.getCompanyId());
        return ResponseEntity.ok(Map.of("hasBroker", has));
    }
}
