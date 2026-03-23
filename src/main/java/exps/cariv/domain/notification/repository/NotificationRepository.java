package exps.cariv.domain.notification.repository;

import exps.cariv.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByCompanyIdAndId(Long companyId, Long id);

    Page<Notification> findByCompanyIdAndUserIdAndExpiresAtAfter(
            Long companyId,
            Long userId,
            Instant now,
            Pageable pageable
    );

    long deleteByExpiresAtBefore(Instant now);
}
