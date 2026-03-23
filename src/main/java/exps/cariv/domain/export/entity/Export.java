package exps.cariv.domain.export.entity;

import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 수출신고필증 OCR 파싱 결과.
 * <p>문서 업로드 → OCR → 파싱 → Export 엔티티 저장.</p>
 */
@Entity
@Getter
@Table(indexes = {
        @Index(name = "idx_export_company_vehicle", columnList = "company_id, vehicleId"),
        @Index(name = "idx_export_company_declno", columnList = "company_id, declarationNo"),
        @Index(name = "idx_export_company_vin", columnList = "company_id, chassisNo")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Export extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 연결 차량 ID */
    @Column(nullable = false)
    private Long vehicleId;

    /** 신고번호 (예: 44475-25-010329X) */
    @Column(length = 50)
    private String declarationNo;

    /** 신고일자 */
    private LocalDate declarationDate;

    /** 신고수리일자 */
    private LocalDate acceptanceDate;

    /** 발행번호 */
    @Column(length = 30)
    private String issueNo;

    /** 목적국 코드 */
    @Column(length = 10)
    private String destCountryCode;

    /** 목적국명 */
    @Column(length = 50)
    private String destCountryName;

    /** 적재항 코드 */
    @Column(length = 20)
    private String loadingPortCode;

    /** 적재항명 */
    @Column(length = 50)
    private String loadingPortName;

    /** 컨테이너 번호 */
    @Column(length = 30)
    private String containerNo;

    /** 거래품명(차명) */
    @Column(length = 100)
    private String itemName;

    /** 연식 */
    @Column(length = 10)
    private String modelYear;

    /** VIN(차대번호) */
    @Column(length = 50)
    private String chassisNo;

    /** 신고금액(KRW) */
    private Long amountKrw;

    /** 적재의무기한 */
    private LocalDate loadingDeadline;

    /** 구매자(바이어)명 */
    @Column(length = 100)
    private String buyerName;

    // ─── 업데이트 메서드 (OCR 결과 확인 후 수동 수정용) ───

    public void updateDeclarationNo(String declarationNo) { this.declarationNo = declarationNo; }
    public void updateDeclarationDate(LocalDate declarationDate) { this.declarationDate = declarationDate; }
    public void updateAcceptanceDate(LocalDate acceptanceDate) { this.acceptanceDate = acceptanceDate; }
    public void updateIssueNo(String issueNo) { this.issueNo = issueNo; }
    public void updateDestCountryCode(String destCountryCode) { this.destCountryCode = destCountryCode; }
    public void updateDestCountryName(String destCountryName) { this.destCountryName = destCountryName; }
    public void updateLoadingPortCode(String loadingPortCode) { this.loadingPortCode = loadingPortCode; }
    public void updateLoadingPortName(String loadingPortName) { this.loadingPortName = loadingPortName; }
    public void updateContainerNo(String containerNo) { this.containerNo = containerNo; }
    public void updateItemName(String itemName) { this.itemName = itemName; }
    public void updateModelYear(String modelYear) { this.modelYear = modelYear; }
    public void updateChassisNo(String chassisNo) { this.chassisNo = chassisNo; }
    public void updateAmountKrw(Long amountKrw) { this.amountKrw = amountKrw; }
    public void updateLoadingDeadline(LocalDate loadingDeadline) { this.loadingDeadline = loadingDeadline; }
    public void updateBuyerName(String buyerName) { this.buyerName = buyerName; }
}
