package exps.cariv.domain.customs.entity;

import exps.cariv.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 관세사 전송 요청에 포함된 차량 정보.
 * <p>차량별 금액, 거래조건, 차량 사진 S3 key 를 보관한다.</p>
 */
@Entity
@Getter
@Table(indexes = {
        @Index(name = "idx_crv_request", columnList = "customs_request_id"),
        @Index(name = "idx_crv_vehicle", columnList = "vehicleId")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CustomsRequestVehicle extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customs_request_id", nullable = false)
    private CustomsRequest customsRequest;

    @Column(nullable = false)
    private Long vehicleId;

    // ─── 금액 / 거래조건 ───
    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TradeCondition tradeCondition;

    // ─── CIF/CFR 추가 비용 ───
    private Long shippingFee;       // 운임료 (CIF, CFR)
    private Long insuranceFee;      // 보험료 (CIF)
    private Long otherFee;          // 기타금액 (CIF)

    // ─── Container 전용: 차량 사진 (최대 4장) ───
    @Column(length = 400)
    private String vehiclePhoto1S3Key;

    @Column(length = 400)
    private String vehiclePhoto2S3Key;

    @Column(length = 400)
    private String vehiclePhoto3S3Key;

    @Column(length = 400)
    private String vehiclePhoto4S3Key;

    /**
     * 차량 사진 슬롯에서 동일한 key를 찾아 제거한다.
     */
    public void removeVehiclePhotoKey(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return;
        }
        if (s3Key.equals(this.vehiclePhoto1S3Key)) {
            this.vehiclePhoto1S3Key = null;
        } else if (s3Key.equals(this.vehiclePhoto2S3Key)) {
            this.vehiclePhoto2S3Key = null;
        } else if (s3Key.equals(this.vehiclePhoto3S3Key)) {
            this.vehiclePhoto3S3Key = null;
        } else if (s3Key.equals(this.vehiclePhoto4S3Key)) {
            this.vehiclePhoto4S3Key = null;
        }
    }

}
