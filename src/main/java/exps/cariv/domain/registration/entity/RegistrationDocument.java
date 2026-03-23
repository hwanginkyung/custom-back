package exps.cariv.domain.registration.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.registration.dto.RegistrationParsed;
import exps.cariv.domain.registration.dto.RegistrationSnapshot;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "registration_document")
public class RegistrationDocument extends Document {

    // === Vehicle과 컬럼명 통일 ===
    private String vin;
    private String vehicleNo;
    private String carType;
    private String vehicleUse;
    private String modelName;
    private String engineType;
    private String ownerName;
    private String ownerId;
    private Integer modelYear;
    private String fuelType;
    @Column(length = 7)
    private String manufactureYearMonth;
    private Integer displacement;
    private LocalDate firstRegistratedAt;
    private Long mileageKm;

    // === 등록증에만 있는 값 ===
    private String address;
    private String modelCode;

    // 제원
    private String lengthVal;
    private String widthVal;
    private String heightVal;
    private String weight;
    private String seating;
    private String maxLoad;
    private String power;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String rawJson; // Upstage table payload(디버그/재현용)

    private Instant parsedAt;

    public static RegistrationDocument createNew(Long companyId,
                                                 Long uploadedByUserId,
                                                 Long vehicleId,
                                                 String s3Key,
                                                 String originalFilename) {
        RegistrationDocument doc = RegistrationDocument.builder().build();
        doc.companyId = companyId;
        doc.type = DocumentType.REGISTRATION;
        doc.status = DocumentStatus.UPLOADED;
        doc.refType = DocumentRefType.VEHICLE;
        doc.refId = vehicleId;
        doc.s3Key = s3Key;
        doc.uploadedByUserId = uploadedByUserId;
        doc.originalFilename = originalFilename;
        doc.uploadedAt = Instant.now();
        return doc;
    }

    /**
     * OCR 파싱 결과를 엔티티에 반영한다.
     */
    public void applyOcrResult(RegistrationParsed p, String rawJson) {
        if (p == null) return;

        this.vin = p.vin();
        this.vehicleNo = p.vehicleNo();
        this.carType = p.carType();
        this.vehicleUse = p.vehicleUse();
        this.modelName = p.modelName();
        this.engineType = p.engineType();
        this.mileageKm = p.mileageKm();
        this.ownerName = p.ownerName();
        this.ownerId = p.ownerId();
        this.modelYear = p.modelYear();
        this.fuelType = p.fuelType();
        this.manufactureYearMonth = p.manufactureYearMonth();
        this.displacement = p.displacement();
        this.firstRegistratedAt = p.firstRegistratedAt();

        this.address = p.addressText();
        this.modelCode = p.modelCode();

        this.lengthVal = intToStr(p.lengthMm());
        this.widthVal = intToStr(p.widthMm());
        this.heightVal = intToStr(p.heightMm());
        this.weight = intToStr(p.weightKg());
        this.seating = intToStr(p.seating());
        this.maxLoad = intToStr(p.maxLoadKg());
        this.power = intToStr(p.powerKw());

        this.rawJson = rawJson;
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    /**
     * 현재 엔티티 상태를 RegistrationSnapshot으로 변환(조회 응답용).
     */
    public RegistrationSnapshot toSnapshot() {
        return new RegistrationSnapshot(
                vin, vehicleNo, carType, vehicleUse,
                modelName, engineType, ownerName, ownerId,
                modelYear, fuelType, manufactureYearMonth, displacement, firstRegistratedAt, mileageKm,
                address, modelCode,
                lengthVal, widthVal, heightVal, weight, seating, maxLoad, power
        );
    }

    /**
     * 수동 수정된 스냅샷을 엔티티에 반영한다.
     * 문자열 공백은 trim 후 저장하며 빈 문자열은 null 처리한다.
     */
    public void applyManualSnapshot(RegistrationSnapshot s) {
        if (s == null) return;

        this.vin = normalizeVin(s.vin());
        this.vehicleNo = normalize(s.vehicleNo());
        this.carType = normalize(s.carType());
        this.vehicleUse = normalize(s.vehicleUse());
        this.modelName = normalize(s.modelName());
        this.engineType = normalize(s.engineType());
        this.ownerName = normalize(s.ownerName());
        this.ownerId = normalize(s.ownerId());
        this.modelYear = s.modelYear();
        this.fuelType = normalize(s.fuelType());
        this.manufactureYearMonth = normalize(s.manufactureYearMonth());
        this.displacement = s.displacement();
        this.firstRegistratedAt = s.firstRegistratedAt();
        this.mileageKm = s.mileageKm();

        this.address = normalize(s.address());
        this.modelCode = normalize(s.modelCode());

        this.lengthVal = normalize(s.lengthVal());
        this.widthVal = normalize(s.widthVal());
        this.heightVal = normalize(s.heightVal());
        this.weight = normalize(s.weight());
        this.seating = normalize(s.seating());
        this.maxLoad = normalize(s.maxLoad());
        this.power = normalize(s.power());

        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    private static String intToStr(Integer v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String normalize(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeVin(String vin) {
        if (vin == null) return null;
        String cleaned = vin.replaceAll("\\s+", "").toUpperCase();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
