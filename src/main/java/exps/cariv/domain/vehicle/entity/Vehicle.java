package exps.cariv.domain.vehicle.entity;

import exps.cariv.domain.vehicle.dto.VehiclePatch;
import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Getter
@Table(indexes = {
        @Index(name = "idx_vehicle_company_vin", columnList = "company_id,vin"),
        @Index(name = "idx_vehicle_company_vehicleNo", columnList = "company_id,vehicleNo"),
        @Index(name = "idx_vehicle_company_stage", columnList = "company_id,stage"),
        @Index(name = "idx_vehicle_company_deleted", columnList = "company_id,deleted")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Vehicle extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String vin;                          // 차대번호

    @Column(length = 50)
    private String vehicleNo;                    // 차량번호(등록번호)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private VehicleStage stage = VehicleStage.BEFORE_DEREGISTRATION;

    private String carType;                      // 차종
    private String vehicleUse;                   // 용도
    private String modelName;                    // 차명
    private String engineType;                   // 원동기 형식
    private Long mileageKm;                      // 주행거리(km)
    private String ownerName;                    // 소유자명
    private String ownerId;                      // 소유자 ID(주민/사업자번호)
    private Integer modelYear;                   // 연식

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private OwnerType ownerType;                 // 소유자유형

    @Column(length = 7)
    private String manufactureYearMonth;         // 제작연월(yyyy-MM)

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TransmissionType transmission;       // 변속기

    private String fuelType;                     // 연료
    private Integer displacement;                // 배기량(cc)
    private LocalDate firstRegistrationDate;     // 최초등록일
    private String color;                        // 색상
    private Integer weight;                      // 중량(kg)
    private Integer seatingCapacity;             // 승차정원
    private Integer length;                      // 전장(mm)
    private Integer height;                      // 전고(mm)
    private Integer width;                       // 전폭(mm)
    private String shipperName;                  // 화주명

    // ─── 매입정보 ───
    private Long shipperId;                      // 매입처(화주) FK
    private Long purchasePrice;                  // 매입가
    private LocalDate purchaseDate;              // 매입일
    private LocalDate licenseDate;               // 면허일

    // ─── 매출정보 ───
    private Long saleAmount;                     // 판매금액
    private LocalDate saleDate;                  // 매출일

    // ─── 선적/신고 정보 ───
    @Column(length = 20)
    private String shippingMethod;               // 선적방식: RORO / CONTAINER

    private LocalDate deRegistrationDate;        // 말소등록일 (말소증 OCR에서 파싱)

    @Column(name = "export_invoice_no", length = 80)
    private String exportInvoiceNo;              // 인보이스 번호 (말소/신고 공통 고정값)

    // ─── 환급 신청 ───
    @Column(name = "refund_applied", columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean refundApplied = false;       // 환급 엑셀 다운로드 시 true

    // ─── 말소 출력 이력 ───
    private Instant malsoPrintedAt;              // 전체출력 최초 실행 시각 (null이면 미출력)

    // ─── soft delete ───
    @Builder.Default
    private boolean deleted = false;

    // ─── 수정 메서드 ───

    public void applyPatch(VehiclePatch p) {
        if (p == null) return;

        if (has(p.vin()))         this.vin = p.vin().replaceAll("\\s+", "").toUpperCase();
        if (has(p.vehicleNo()))   this.vehicleNo = p.vehicleNo().trim();
        if (has(p.carType()))     this.carType = p.carType().trim();
        if (has(p.vehicleUse()))  this.vehicleUse = p.vehicleUse().trim();
        if (has(p.modelName()))   this.modelName = p.modelName().trim();
        if (has(p.engineType()))  this.engineType = p.engineType().trim();
        if (p.mileageKm() != null)           this.mileageKm = p.mileageKm();
        if (has(p.ownerName()))   this.ownerName = p.ownerName().trim();
        if (has(p.ownerId()))     this.ownerId = p.ownerId().trim();
        if (p.modelYear() != null)           this.modelYear = p.modelYear();
        if (p.ownerType() != null)           this.ownerType = p.ownerType();
        if (has(p.manufactureYearMonth()))   this.manufactureYearMonth = p.manufactureYearMonth().trim();
        if (p.displacement() != null)        this.displacement = p.displacement();
        if (p.firstRegistrationDate() != null) this.firstRegistrationDate = p.firstRegistrationDate();
        if (p.transmission() != null)        this.transmission = p.transmission();
        if (has(p.fuelType()))     this.fuelType = p.fuelType().trim();
        if (has(p.color()))        this.color = p.color().trim();
        if (p.weight() != null)              this.weight = p.weight();
        if (p.seatingCapacity() != null)     this.seatingCapacity = p.seatingCapacity();
        if (p.length() != null)              this.length = p.length();
        if (p.height() != null)              this.height = p.height();
        if (p.width() != null)               this.width = p.width();
    }

    /** 전체 수정용 (차량 수정 화면) */
    public void applyFullUpdate(VehiclePatch p,
                                String shipperName,
                                Long shipperId,
                                OwnerType ownerType,
                                Long purchasePrice,
                                LocalDate purchaseDate,
                                Long saleAmount,
                                LocalDate saleDate) {
        applyPatch(p);
        if (shipperName != null) this.shipperName = shipperName.trim();
        if (shipperId != null) this.shipperId = shipperId;
        if (ownerType != null) this.ownerType = ownerType;
        if (purchasePrice != null) this.purchasePrice = purchasePrice;
        if (purchaseDate != null) this.purchaseDate = purchaseDate;
        if (saleAmount != null) this.saleAmount = saleAmount;
        if (saleDate != null) this.saleDate = saleDate;
    }

    public void softDelete() { this.deleted = true; }

    public void updateStage(VehicleStage stage) { this.stage = stage; }

    public void assignShipper(Long shipperId, String shipperName) {
        this.shipperId = shipperId;
        this.shipperName = shipperName;
    }

    /** 선적방식 설정 */
    public void updateShippingMethod(String method) {
        this.shippingMethod = method;
    }

    public void updatePurchasePrice(Long purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public void updateLicenseDate(LocalDate licenseDate) {
        this.licenseDate = licenseDate;
    }

    public void updateExportInvoiceNo(String exportInvoiceNo) {
        this.exportInvoiceNo = exportInvoiceNo;
    }

    /** null-safe 환급 신청 여부 조회 */
    public boolean isRefundApplied() {
        return Boolean.TRUE.equals(this.refundApplied);
    }

    /** 환급 신청 완료 처리 (엑셀 다운로드 시점) */
    public void markRefundApplied() {
        this.refundApplied = true;
    }

    /** 말소등록일 설정 (OCR 결과 반영용) */
    public void updateDeRegistrationDate(LocalDate date) {
        this.deRegistrationDate = date;
    }

    /** 말소 전체출력 최초 실행 기록. 이미 기록되어 있으면 무시합니다. */
    public void markMalsoPrinted() {
        if (this.malsoPrintedAt == null) {
            this.malsoPrintedAt = Instant.now();
        }
    }

    /** 출력 이력 존재 여부 */
    public boolean hasMalsoPrintHistory() {
        return this.malsoPrintedAt != null;
    }

    private boolean has(String s) { return s != null && !s.trim().isBlank(); }

}
