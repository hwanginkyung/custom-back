package exps.customs.domain.client.controller;

import exps.customs.domain.client.dto.ClientResponse;
import exps.customs.domain.client.dto.CreateClientRequest;
import exps.customs.domain.client.dto.UpdateClientRequest;
import exps.customs.domain.client.service.ClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
@Tag(name = "Client", description = "화주 관리 API")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;

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
}
