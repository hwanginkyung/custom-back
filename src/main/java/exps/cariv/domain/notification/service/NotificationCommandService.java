package exps.cariv.domain.notification.service;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.notification.dto.response.NotificationItemResponse;
import exps.cariv.domain.notification.entity.Notification;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.repository.NotificationRepository;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCommandService {

    private static final int TITLE_MAX_LEN = 255;
    private static final int BODY_MAX_LEN = 255;

    private final NotificationRepository repo;
    private final OcrParseJobRepository ocrJobRepo;
    private final SseHub sseHub;
    private final PlatformTransactionManager txManager;

    /**
     * OCR 관련 알림 생성 + SSE push (best-effort)
     * - 자동 삭제: expiresAt = now + 3days
     */
    public Notification createOcr(Long companyId,
                                  Long userId,
                                  NotificationType type,
                                  DocumentType documentType,
                                  Long vehicleId,
                                  Long jobId,
                                  String title,
                                  String body) {

        Long normalizedVehicleId = normalizeVehicleId(vehicleId);
        String normalizedTitle = trimToMax(title, TITLE_MAX_LEN);
        String normalizedBody = trimToMax(body, BODY_MAX_LEN);

        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            Notification saved = tx.execute(status -> {
                Instant now = Instant.now();

                Notification n = Notification.builder()
                        .userId(userId)
                        .type(type)
                        .documentType(documentType)
                        .vehicleId(normalizedVehicleId)
                        .jobId(jobId)
                        .title(normalizedTitle)
                        .body(normalizedBody)
                        .read(false)
                        .expiresAt(now.plus(3, ChronoUnit.DAYS))
                        .build();
                n.setCompanyId(companyId);
                return repo.save(n);
            });
            if (saved == null) {
                return null;
            }

            try {
                sseHub.push(companyId, userId, "notification", toDto(saved));
            } catch (Exception e) {
                log.warn("[SSE] push failed userId={}, notificationId={}: {}", userId, saved.getId(), e.getMessage());
            }

            return saved;
        } catch (Exception e) {
            log.error("[Notification] createOcr failed companyId={} userId={} jobId={} vehicleId={} type={} documentType={}",
                    companyId, userId, jobId, normalizedVehicleId, type, documentType, e);
            return null;
        }
    }

    @Transactional
    public Notification markRead(Long companyId, Long userId, Long notificationId) {
        Notification n = repo.findByCompanyIdAndId(companyId, notificationId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (!n.getUserId().equals(userId)) throw new CustomException(ErrorCode.FORBIDDEN);

        if (!n.isRead()) {
            n.markRead();
            repo.save(n);
        }
        return n;
    }

    @Transactional
    public void delete(Long companyId, Long userId, Long notificationId) {
        Notification n = repo.findByCompanyIdAndId(companyId, notificationId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (!n.getUserId().equals(userId)) throw new CustomException(ErrorCode.FORBIDDEN);

        repo.delete(n);
    }

    private NotificationItemResponse toDto(Notification n) {
        Long documentId = (n.getJobId() == null || n.getJobId() <= 0)
                ? null
                : ocrJobRepo.findByCompanyIdAndId(n.getCompanyId(), n.getJobId())
                .map(job -> job.getVehicleDocumentId())
                .orElse(null);

        return new NotificationItemResponse(
                n.getId(),
                n.getType(),
                n.getDocumentType(),
                n.getVehicleId(),
                n.getJobId(),
                documentId,
                n.getTitle(),
                n.getBody(),
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt(),
                n.getExpiresAt()
        );
    }

    private Long normalizeVehicleId(Long vehicleId) {
        if (vehicleId == null || vehicleId < 0) {
            return 0L;
        }
        return vehicleId;
    }

    private String trimToMax(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
