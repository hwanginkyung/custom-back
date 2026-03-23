package exps.cariv.domain.malso.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.malso.dto.ParsedDereg;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 말소증(자동차 말소 사실 증명서) 문서 엔티티.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DiscriminatorValue("DEREGISTRATION")
@Table(name = "deregistration")
public class Deregistration extends Document {

    // === 말소증 고유 필드 ===
    private String documentNo;              // 증명번호
    private String specNo;                  // 규격번호
    private String registrationNo;          // 등록번호 (=vehicleNo)

    private LocalDate deRegistrationDate;   // 말소등록 일자
    private String deRegistrationReason;    // 말소 등록 사유
    private String rightsRelation;          // 권리관계 정보

    // === Vehicle 반영용 ===
    private String vin;                     // 차대번호
    private String modelName;               // 차명
    private Integer modelYear;              // 연식
    private String ownerName;               // 소유자명
    private String ownerId;                 // 생년월일/법인등록번호
    private Long mileageKm;                 // 주행거리

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String rawJson;

    private Instant parsedAt;

    /**
     * 새 말소증 문서 생성 (업로드 시).
     */
    public static Deregistration createNew(Long companyId, Long uploadedByUserId,
                                            Long vehicleId, String s3Key, String originalFilename) {
        Deregistration d = new Deregistration();
        d.companyId = companyId;
        d.type = DocumentType.DEREGISTRATION;
        d.status = DocumentStatus.UPLOADED;
        d.refType = DocumentRefType.VEHICLE;
        d.refId = vehicleId;
        d.s3Key = s3Key;
        d.uploadedByUserId = uploadedByUserId;
        d.originalFilename = originalFilename;
        d.uploadedAt = Instant.now();
        return d;
    }

    /**
     * OCR 파싱 결과 반영.
     */
    public void applyOcrResult(ParsedDereg p, String rawJson) {
        if (p == null) return;

        this.documentNo = p.documentNo();
        this.specNo = p.specNo();
        this.registrationNo = p.registrationNo();
        this.deRegistrationDate = p.deRegistrationDate();
        this.deRegistrationReason = p.deRegistrationReason();
        this.rightsRelation = p.rightsRelation();

        this.vin = p.vin();
        this.modelName = p.modelName();
        this.modelYear = p.modelYear();
        this.ownerName = p.ownerName();
        this.ownerId = p.ownerId();
        this.mileageKm = p.mileageKm();

        this.rawJson = rawJson;
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    /**
     * 수동 수정 (OCR 결과 교정).
     */
    public void manualUpdate(String registrationNo,
                             String vin,
                             String modelName,
                             Integer modelYear,
                             String ownerName,
                             String ownerId,
                             String documentNo,
                             String specNo,
                             LocalDate deRegistrationDate,
                             String deRegistrationReason,
                             String rightsRelation) {
        if (registrationNo != null) this.registrationNo = registrationNo;
        if (vin != null) this.vin = vin;
        if (modelName != null) this.modelName = modelName;
        if (modelYear != null) this.modelYear = modelYear;
        if (ownerName != null) this.ownerName = ownerName;
        if (ownerId != null) this.ownerId = ownerId;
        if (documentNo != null) this.documentNo = documentNo;
        if (specNo != null) this.specNo = specNo;
        if (deRegistrationDate != null) this.deRegistrationDate = deRegistrationDate;
        if (deRegistrationReason != null) this.deRegistrationReason = deRegistrationReason;
        if (rightsRelation != null) this.rightsRelation = rightsRelation;
    }
}
