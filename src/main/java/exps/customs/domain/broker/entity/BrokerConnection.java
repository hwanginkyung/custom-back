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

    @Column(name = "exporter_business_number", length = 30)
    private String exporterBusinessNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConnectionStatus status = ConnectionStatus.PENDING;

    @Column
    private Instant approvedAt;

    @Column(name = "linked_client_id")
    private Long linkedClientId;

    @Column(name = "linked_at")
    private Instant linkedAt;

    public void approve() {
        approve(null);
    }

    public void approve(Long matchedClientId) {
        this.status = ConnectionStatus.APPROVED;
        this.approvedAt = Instant.now();
        this.linkedClientId = matchedClientId;
        this.linkedAt = matchedClientId == null ? null : Instant.now();
    }

    public void reject() {
        this.status = ConnectionStatus.REJECTED;
        this.approvedAt = null;
        this.linkedClientId = null;
        this.linkedAt = null;
    }

    public void request() {
        this.status = ConnectionStatus.PENDING;
        this.approvedAt = null;
        this.linkedClientId = null;
        this.linkedAt = null;
    }

    public void updateExporterProfile(String companyName, String businessNumber) {
        if (companyName != null && !companyName.isBlank()) {
            this.exporterCompanyName = companyName.trim();
        }
        if (businessNumber != null && !businessNumber.isBlank()) {
            this.exporterBusinessNumber = businessNumber.trim();
        }
    }

    public void updateBrokerCompanyName(String brokerCompanyName) {
        if (brokerCompanyName != null && !brokerCompanyName.isBlank()) {
            this.brokerCompanyName = brokerCompanyName.trim();
        }
    }

    public void disconnect() {
        this.status = ConnectionStatus.DISCONNECTED;
        this.approvedAt = null;
        this.linkedClientId = null;
        this.linkedAt = null;
    }

    public void linkClient(Long clientId) {
        this.linkedClientId = clientId;
        this.linkedAt = clientId == null ? null : Instant.now();
    }
}
