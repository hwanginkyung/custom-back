package exps.cariv.domain.customs.entity;

import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 관세사 전송 요청.
 * <p>하나의 전송 요청은 하나의 선적방식(RORO/CONTAINER)에 해당하며,
 * 1 ~ N 대의 차량을 포함한다.</p>
 */
@Entity
@Getter
@Table(indexes = {
        @Index(name = "idx_cr_company_status", columnList = "company_id,status"),
        @Index(name = "idx_cr_company_broker", columnList = "company_id,customsBrokerId"),
        @Index(name = "uk_cr_company_invoice_no", columnList = "company_id,invoice_no", unique = true)
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CustomsRequest extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ShippingMethod shippingMethod;

    // ─── 관세사 정보 ───
    private Long customsBrokerId;          // 관세사(화주/업체) FK
    @Column(length = 100)
    private String customsBrokerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CustomsRequestStatus status = CustomsRequestStatus.DRAFT;

    @Column(name = "invoice_no", length = 80)
    private String invoiceNo;

    // ─── Container 전용 정보 ───
    @Embedded
    private ContainerInfo containerInfo;

    // ─── Container 전용: 요청 공통 사진(최대 3장) ───
    @Column(length = 400)
    private String containerPhoto1S3Key;

    @Column(length = 400)
    private String containerPhoto2S3Key;

    @Column(length = 400)
    private String containerPhoto3S3Key;

    // ─── 상태 전이 ───
    public void submit() {
        this.status = CustomsRequestStatus.PROCESSING;
    }

    public void startProcessing() {
        this.status = CustomsRequestStatus.PROCESSING;
    }

    public void complete() {
        this.status = CustomsRequestStatus.COMPLETED;
    }

    // ─── 다시 보내기 (resend) 시 필드 업데이트 ───
    public void updateDraft(ShippingMethod shippingMethod,
                            Long customsBrokerId,
                            String customsBrokerName,
                            ContainerInfo containerInfo,
                            String containerPhoto1S3Key,
                            String containerPhoto2S3Key,
                            String containerPhoto3S3Key) {
        this.shippingMethod = shippingMethod;
        this.customsBrokerId = customsBrokerId;
        this.customsBrokerName = customsBrokerName;
        this.containerInfo = containerInfo;
        this.containerPhoto1S3Key = containerPhoto1S3Key;
        this.containerPhoto2S3Key = containerPhoto2S3Key;
        this.containerPhoto3S3Key = containerPhoto3S3Key;
        this.status = CustomsRequestStatus.DRAFT;
    }

    // ─── 다시 보내기 (resend) 시 필드 업데이트 ───
    public void updateForResend(ShippingMethod shippingMethod,
                                Long customsBrokerId,
                                String customsBrokerName,
                                ContainerInfo containerInfo,
                                String containerPhoto1S3Key,
                                String containerPhoto2S3Key,
                                String containerPhoto3S3Key) {
        this.shippingMethod = shippingMethod;
        this.customsBrokerId = customsBrokerId;
        this.customsBrokerName = customsBrokerName;
        this.containerInfo = containerInfo;
        this.containerPhoto1S3Key = containerPhoto1S3Key;
        this.containerPhoto2S3Key = containerPhoto2S3Key;
        this.containerPhoto3S3Key = containerPhoto3S3Key;
        this.status = CustomsRequestStatus.PROCESSING;
    }

    public void assignInvoiceNoIfAbsent(String invoiceNo) {
        if (this.invoiceNo == null || this.invoiceNo.isBlank()) {
            this.invoiceNo = invoiceNo;
        }
    }

    /**
     * 컨테이너 사진 슬롯에서 동일한 key를 찾아 제거한다.
     */
    public void removeContainerPhotoKey(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return;
        }
        if (s3Key.equals(this.containerPhoto1S3Key)) {
            this.containerPhoto1S3Key = null;
        } else if (s3Key.equals(this.containerPhoto2S3Key)) {
            this.containerPhoto2S3Key = null;
        } else if (s3Key.equals(this.containerPhoto3S3Key)) {
            this.containerPhoto3S3Key = null;
        }
    }
}
