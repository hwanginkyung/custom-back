package exps.cariv.domain.broker.entity;

import exps.cariv.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 수출자(화주) ↔ 관세사 연동 테이블.
 * 같은 DB를 cariv · cariv-customs 두 앱이 공유하므로
 * 양쪽 모두 이 테이블을 읽고 쓴다.
 */
@Entity
@Table(
        name = "broker_connection",
        indexes = {
                @Index(name = "idx_bc_exporter", columnList = "exporter_company_id"),
                @Index(name = "idx_bc_broker", columnList = "broker_company_id"),
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_bc_exporter_broker",
                        columnNames = {"exporter_company_id", "broker_company_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BrokerConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수출자(화주) 회사 ID – cariv 쪽 Company.id */
    @Column(name = "exporter_company_id", nullable = false)
    private Long exporterCompanyId;

    /** 관세사 회사 ID – cariv-customs 쪽 Company.id */
    @Column(name = "broker_company_id", nullable = false)
    private Long brokerCompanyId;

    /** 관세사 회사명 (비정규화 – 조회 편의) */
    @Column(name = "broker_company_name", length = 100)
    private String brokerCompanyName;

    /** 수출자 회사명 (비정규화 – 관세사 쪽 조회 편의) */
    @Column(name = "exporter_company_name", length = 100)
    private String exporterCompanyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConnectionStatus status = ConnectionStatus.PENDING;

    @Column
    private Instant approvedAt;

    public void approve() {
        this.status = ConnectionStatus.APPROVED;
        this.approvedAt = Instant.now();
    }

    public void reject() {
        this.status = ConnectionStatus.REJECTED;
    }
}
