package exps.cariv.domain.notification.dto.response;

import java.time.Instant;

public record NotificationReadResponse(
        Long notificationId,
        boolean read,
        Instant readAt,
        String message
) {}
