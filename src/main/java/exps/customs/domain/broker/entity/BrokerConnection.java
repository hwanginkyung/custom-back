package exps.customs.domain.broker.entity;

import exps.customs.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 수출자(화주) ↔ 관세사 연동 테이블.
 * cariv 쪽과 동일 테이블을 공유한다.
 */
@Entity
@Table(name = "broker_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class BrokerConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exporter_company_id", nullable = false)
    private Long exporterCompanyId;

    @Column(name = "broker_company_id", nullable = false)
    private Long brokerCompanyId;

    @Column(name = "broker_company_name", length = 100)
    private String brokerCompanyName;

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
