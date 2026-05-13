package exps.customs.domain.notification.repository;

import exps.customs.domain.notification.entity.BrokerNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BrokerNotificationRepository extends JpaRepository<BrokerNotification, Long> {

    List<BrokerNotification> findTop20ByCompanyIdOrderByCreatedAtDesc(Long companyId);

    long countByCompanyIdAndReadAtIsNull(Long companyId);

    Optional<BrokerNotification> findByIdAndCompanyId(Long id, Long companyId);

    @Modifying
    @Query("""
            update BrokerNotification n
               set n.readAt = :readAt
             where n.companyId = :companyId
               and n.readAt is null
            """)
    int markAllRead(@Param("companyId") Long companyId, @Param("readAt") Instant readAt);
}
