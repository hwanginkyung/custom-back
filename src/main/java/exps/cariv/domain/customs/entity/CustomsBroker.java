package exps.cariv.domain.customs.entity;

import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 관세사 마스터.
 * 화주(Shipper)와 분리된 독립 도메인으로 관리한다.
 */
@Entity
@Getter
@Table(
        indexes = {
                @Index(name = "idx_customs_broker_company_active", columnList = "company_id,active"),
                @Index(name = "idx_customs_broker_company_name", columnList = "company_id,name")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_customs_broker_company_business_no",
                        columnNames = {"company_id", "business_no"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CustomsBroker extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(name = "business_no", length = 40)
    private String businessNo;

    @Column(length = 120)
    private String email;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public void update(String name, String phone, String email) {
        this.name = name;
        this.phone = phone;
        this.email = email;
    }

    public void deactivate() {
        this.active = false;
    }
}
