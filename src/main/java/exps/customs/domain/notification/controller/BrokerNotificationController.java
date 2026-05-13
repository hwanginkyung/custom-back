package exps.customs.domain.notification.controller;

import exps.customs.domain.notification.dto.BrokerNotificationResponse;
import exps.customs.domain.notification.dto.BrokerNotificationSummaryResponse;
import exps.customs.domain.notification.service.BrokerNotificationService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "관세사 알림 API")
public class BrokerNotificationController {

    private final BrokerNotificationService notificationService;

    @GetMapping
    @Operation(summary = "최근 알림 목록")
    public ResponseEntity<List<BrokerNotificationResponse>> listRecent(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(notificationService.listRecent(me.getCompanyId()));
    }

    @GetMapping("/summary")
    @Operation(summary = "알림 요약")
    public ResponseEntity<BrokerNotificationSummaryResponse> getSummary(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        return ResponseEntity.ok(notificationService.getSummary(me.getCompanyId()));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "알림 읽음 처리")
    public ResponseEntity<String> markRead(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long notificationId
    ) {
        notificationService.markRead(me.getCompanyId(), notificationId);
        return ResponseEntity.ok("ok");
    }

    @PatchMapping("/read-all")
    @Operation(summary = "전체 알림 읽음 처리")
    public ResponseEntity<String> markAllRead(
            @AuthenticationPrincipal CustomUserDetails me
    ) {
        notificationService.markAllRead(me.getCompanyId());
        return ResponseEntity.ok("ok");
    }
}
