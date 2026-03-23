package exps.cariv.domain.customs.controller;

import exps.cariv.domain.customs.dto.request.CustomsBrokerCreateRequest;
import exps.cariv.domain.customs.dto.request.CustomsBrokerUpdateRequest;
import exps.cariv.domain.customs.dto.response.CustomsBrokerResponse;
import exps.cariv.domain.customs.service.CustomsBrokerService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customs/brokers")
@RequiredArgsConstructor
@Tag(name = "Customs Broker", description = "관세사 마스터 API")
public class CustomsBrokerController {

    private final CustomsBrokerService brokerService;

    @GetMapping
    @Operation(summary = "관세사 목록 조회", description = "회사별 관세사 목록을 조회합니다.")
    public ResponseEntity<List<CustomsBrokerResponse>> list(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(brokerService.list(me.getCompanyId()));
    }

    @PostMapping
    @Operation(summary = "관세사 등록", description = "관세사를 신규 등록합니다.")
    public ResponseEntity<Long> create(
            @AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody CustomsBrokerCreateRequest req
    ) {
        return ResponseEntity.ok(brokerService.create(me.getCompanyId(), req));
    }

    @PatchMapping("/{brokerId}")
    @Operation(summary = "관세사 수정", description = "관세사 정보를 수정합니다.")
    public ResponseEntity<Void> update(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long brokerId,
            @Valid @RequestBody CustomsBrokerUpdateRequest req
    ) {
        brokerService.update(me.getCompanyId(), brokerId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{brokerId}")
    @Operation(summary = "관세사 삭제", description = "관세사를 비활성 처리합니다.")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long brokerId
    ) {
        brokerService.delete(me.getCompanyId(), brokerId);
        return ResponseEntity.noContent().build();
    }
}
