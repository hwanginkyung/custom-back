package exps.customs.domain.client.controller;

import exps.customs.domain.client.dto.ClientResponse;
import exps.customs.domain.client.dto.ClientSyncConfigResponse;
import exps.customs.domain.client.dto.ClientSyncPushRequest;
import exps.customs.domain.client.dto.ClientSyncResponse;
import exps.customs.domain.client.dto.CreateClientRequest;
import exps.customs.domain.client.dto.UpdateClientRequest;
import exps.customs.domain.client.service.ClientService;
import exps.customs.domain.client.service.ClientSyncService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
@Tag(name = "Client", description = "화주 관리 API")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;
    private final ClientSyncService clientSyncService;

    @GetMapping
    @Operation(summary = "화주 목록 조회")
    public ResponseEntity<List<ClientResponse>> getAll() {
        return ResponseEntity.ok(clientService.getAll());
    }

    @GetMapping("/active")
    @Operation(summary = "활성 화주 목록 조회")
    public ResponseEntity<List<ClientResponse>> getActiveClients() {
        return ResponseEntity.ok(clientService.getActiveClients());
    }

    @GetMapping("/sync/config")
    @Operation(summary = "화주 동기화 모드 설정 조회", description = "서버 Pull 가능 여부와 로컬 에이전트 Push 설정 여부를 조회합니다.")
    public ResponseEntity<ClientSyncConfigResponse> getSyncConfig() {
        return ResponseEntity.ok(clientSyncService.getSyncConfig());
    }

    @GetMapping("/{id}")
    @Operation(summary = "화주 상세 조회")
    public ResponseEntity<ClientResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(clientService.getById(id));
    }

    @PostMapping
    @Operation(summary = "화주 생성")
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody CreateClientRequest req) {
        return ResponseEntity.ok(clientService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "화주 수정")
    public ResponseEntity<ClientResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateClientRequest req) {
        return ResponseEntity.ok(clientService.update(id, req));
    }

    @PatchMapping("/{id}/toggle-active")
    @Operation(summary = "화주 활성/비활성 토글")
    public ResponseEntity<String> toggleActive(@PathVariable Long id) {
        clientService.toggleActive(id);
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/sync")
    @Operation(summary = "통관 프로그램 화주 동기화(Pull)", description = "NCustoms DDeal에서 화주를 읽어와 현재 회사 화주 마스터에 업서트합니다.")
    public ResponseEntity<ClientSyncResponse> syncFromNcustoms(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "00") String codePrefix,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") Integer limit
    ) {
        return ResponseEntity.ok(clientSyncService.syncFromNcustoms(me.getCompanyId(), codePrefix, keyword, limit));
    }

    @PostMapping("/synchronize")
    @Operation(summary = "통관 프로그램 화주 동기화(Pull) 별칭")
    public ResponseEntity<ClientSyncResponse> synchronizeFromNcustoms(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "00") String codePrefix,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") Integer limit
    ) {
        return ResponseEntity.ok(clientSyncService.syncFromNcustoms(me.getCompanyId(), codePrefix, keyword, limit));
    }

    @PostMapping("/sync/push")
    @Operation(summary = "로컬 에이전트 화주 동기화(Push)", description = "로컬 통관프로그램 에이전트가 화주 배치를 업로드합니다. X-Agent-Token 헤더가 필요합니다.")
    public ResponseEntity<ClientSyncResponse> syncFromAgentPush(
            @RequestHeader(name = "X-Agent-Token", required = false) String agentToken,
            @Valid @RequestBody ClientSyncPushRequest request
    ) {
        return ResponseEntity.ok(clientSyncService.syncFromPush(request, agentToken));
    }
}
