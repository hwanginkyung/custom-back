package exps.cariv.domain.notification.controller;

import exps.cariv.domain.notification.dto.response.NotificationItemResponse;
import exps.cariv.domain.notification.dto.response.NotificationReadResponse;
import exps.cariv.domain.notification.entity.Notification;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.notification.service.NotificationQueryService;
import exps.cariv.domain.notification.service.SseHub;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
@Tag(name = "Notification", description = "알림 조회/읽음/삭제 및 SSE 구독 API")
public class NotificationController {

    private final NotificationQueryService queryService;
    private final NotificationCommandService commandService;
    private final SseHub sseHub;

    @GetMapping
    @Operation(
            summary = "알림 목록 조회",
            description = "사용자의 최근 알림 목록을 size 기준으로 조회합니다."
    )
    public List<NotificationItemResponse> list(
            @AuthenticationPrincipal CustomUserDetails me,
            @RequestParam(defaultValue = "20") int size
    ) {
        return queryService.listItems(me.getCompanyId(), me.getUserId(), size);
    }

    @PatchMapping("/{id}/read")
    @Operation(
            summary = "알림 읽음 처리",
            description = "단일 알림을 읽음 상태로 변경합니다."
    )
    public ResponseEntity<NotificationReadResponse> markRead(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long id
    ) {
        Notification n = commandService.markRead(me.getCompanyId(), me.getUserId(), id);
        return ResponseEntity.ok(new NotificationReadResponse(
                n.getId(),
                n.isRead(),
                n.getReadAt(),
                "알림 읽음 처리 완료"
        ));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "알림 삭제",
            description = "단일 알림을 삭제합니다."
    )
    public void delete(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long id
    ) {
        commandService.delete(me.getCompanyId(), me.getUserId(), id);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "알림 SSE 구독",
            description = "실시간 알림 수신을 위한 SSE 스트림을 연결합니다."
    )
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal CustomUserDetails me) {
        SseEmitter emitter = sseHub.subscribe(me.getCompanyId(), me.getUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
