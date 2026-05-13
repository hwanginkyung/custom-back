package exps.customs.domain.notification.dto;

import java.util.List;

public record BrokerNotificationSummaryResponse(
        long unreadCount,
        List<BrokerNotificationResponse> notifications
) {
}
