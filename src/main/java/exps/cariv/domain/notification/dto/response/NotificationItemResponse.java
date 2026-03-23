package exps.cariv.domain.notification.dto.response;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.notification.entity.NotificationType;

import java.time.Instant;

public record NotificationItemResponse(
        Long id,
        NotificationType type,
        DocumentType documentType,
        Long vehicleId,
        Long jobId,
        Long documentId,
        String title,
        String body,
        boolean read,
        Instant readAt,
        Instant createdAt,
        Instant expiresAt
) {}
