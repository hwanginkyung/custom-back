package exps.customs.domain.broker.controller;

import exps.customs.domain.broker.dto.ConnectionRequestResponse;
import exps.customs.domain.broker.service.BrokerConnectionService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/broker-connection")
@RequiredArgsConstructor
@Tag(name = "Broker Connection", description = "관세사 연동 API (관세사 쪽)")
public class BrokerConnectionController {

    private final BrokerConnectionService service;

    @GetMapping("/requests")
    @Operation(summary = "연동 요청 목록", description = "수출자들이 보낸 연동 요청을 조회합니다.")
    public ResponseEntity<List<ConnectionRequestResponse>> listRequests(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(service.listRequests(me.getCompanyId(), status));
    }

    @GetMapping("/requests/pending-count")
    @Operation(summary = "대기 중 요청 수", description = "승인 대기 중인 연동 요청 수를 조회합니다.")
    public ResponseEntity<Map<String, Long>> pendingCount(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(Map.of("count", service.pendingCount(me.getCompanyId())));
    }

    @PatchMapping("/requests/{connectionId}/approve")
    @Operation(summary = "연동 승인", description = "수출자의 연동 요청을 승인합니다.")
    public ResponseEntity<ConnectionRequestResponse> approve(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long connectionId
    ) {
        return ResponseEntity.ok(service.approve(me.getCompanyId(), connectionId));
    }

    @PatchMapping("/requests/{connectionId}/reject")
    @Operation(summary = "연동 거절", description = "수출자의 연동 요청을 거절합니다.")
    public ResponseEntity<ConnectionRequestResponse> reject(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long connectionId
    ) {
        return ResponseEntity.ok(service.reject(me.getCompanyId(), connectionId));
    }
}
