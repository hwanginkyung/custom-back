package exps.cariv.domain.notification.service;

import exps.cariv.domain.notification.dto.response.NotificationItemResponse;
import exps.cariv.domain.notification.entity.Notification;
import exps.cariv.domain.notification.repository.NotificationRepository;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private final NotificationRepository repo;
    private final OcrParseJobRepository ocrJobRepo;

    @Transactional(readOnly = true)
    public List<Notification> list(Long companyId, Long userId, int size) {
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        var pageable = PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "id"));
        return repo.findByCompanyIdAndUserIdAndExpiresAtAfter(companyId, userId, Instant.now(), pageable)
                .getContent();
    }

    @Transactional(readOnly = true)
    public List<NotificationItemResponse> listItems(Long companyId, Long userId, int size) {
        List<Notification> notifications = list(companyId, userId, size);

        Set<Long> jobIds = notifications.stream()
                .map(Notification::getJobId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, Long> documentIdByJobId = new HashMap<>();
        if (!jobIds.isEmpty()) {
            ocrJobRepo.findAllByCompanyIdAndIdIn(companyId, jobIds).forEach(job ->
                    documentIdByJobId.put(job.getId(), job.getVehicleDocumentId()));
        }

        return notifications.stream()
                .map(n -> new NotificationItemResponse(
                        n.getId(),
                        n.getType(),
                        n.getDocumentType(),
                        n.getVehicleId(),
                        n.getJobId(),
                        documentIdByJobId.get(n.getJobId()),
                        n.getTitle(),
                        n.getBody(),
                        n.isRead(),
                        n.getReadAt(),
                        n.getCreatedAt(),
                        n.getExpiresAt()
                ))
                .toList();
    }

    @Transactional
    public long cleanupExpired() {
        return repo.deleteByExpiresAtBefore(Instant.now());
    }
}
