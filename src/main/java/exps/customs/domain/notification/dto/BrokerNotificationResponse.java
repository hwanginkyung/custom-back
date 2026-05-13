package exps.customs.domain.notification.dto;

import exps.customs.domain.notification.entity.BrokerNotification;

import java.time.Instant;

public record BrokerNotificationResponse(
        Long id,
        String type,
        String title,
        String message,
        String linkPath,
        Long sourceId,
        boolean read,
        Instant createdAt,
        Instant readAt
) {
    public static BrokerNotificationResponse from(BrokerNotification notification) {
        return new BrokerNotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getLinkPath(),
                notification.getSourceId(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getReadAt()
        );
    }
}
