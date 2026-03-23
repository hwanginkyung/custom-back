package exps.cariv.domain.shipper.entity;

import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 화주(매입처) 엔티티.
 * - 회사별로 관리되는 거래처 목록
 * - Vehicle.shipperId 로 참조됨
 */
@Entity
@Getter
@Table(indexes = {
        @Index(name = "idx_shipper_company_name", columnList = "company_id,name")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Shipper extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 화주(매입처) 이름 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 화주 유형: 개인, 딜러, 경매 등 */
    @Column(length = 50)
    private String type;

    /** 사업자 유형: 개인사업자 / 법인사업자 */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ShipperType shipperType;

    /** 연락처 */
    @Column(length = 50)
    private String phone;

    /** 사업자등록번호 */
    @Column(length = 50)
    private String businessNumber;

    /** 주소 */
    @Column(length = 500)
    private String address;

    /** 비활성화 여부 (soft delete) */
    @Builder.Default
    private boolean active = true;

    public void update(String name, String type, ShipperType shipperType, String phone, String businessNumber, String address) {
        if (name != null && !name.isBlank()) this.name = name.trim();
        if (type != null) this.type = type.trim();
        if (shipperType != null) this.shipperType = shipperType;
        if (phone != null) this.phone = phone.trim();
        if (businessNumber != null) this.businessNumber = businessNumber.trim();
        if (address != null) this.address = address.trim();
    }

    public void updatePhone(String phone) {
        this.phone = phone != null ? phone.trim() : null;
    }

    public void updateShipperType(ShipperType shipperType) {
        if (shipperType != null) {
            this.shipperType = shipperType;
        }
    }

    public void deactivate() { this.active = false; }
}
