package exps.cariv.domain.notification.entity;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(
        name="notification",
        indexes = {
                @Index(name="idx_notif_company_user_created", columnList="company_id,user_id,created_at"),
                @Index(name="idx_notif_company_expires", columnList="company_id,expires_at")
        }
)
public class Notification extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable=false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=30)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=30)
    private DocumentType documentType;

    @Column(name = "vehicle_id", nullable=false)
    private Long vehicleId;

    @Column(name = "job_id", nullable=false)
    private Long jobId;

    @Column(name = "is_read", nullable=false)
    private boolean read;

    /** 읽은 시각 (읽기 전에는 null) */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "expires_at", nullable=false)
    private Instant expiresAt;

    private String title;
    private String body;

    public void markRead() {
        this.read = true;
        this.readAt = Instant.now();
    }
}
