package exps.cariv.domain.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupScheduler {

    private final NotificationQueryService queryService;

    // 매시간 만료 알림 정리(3일 지난 것)
    @Scheduled(cron = "0 0 * * * *")
    public void cleanup() {
        long deleted = queryService.cleanupExpired();
        if (deleted > 0) log.info("[Notification] cleaned up {} expired rows", deleted);
    }
}
