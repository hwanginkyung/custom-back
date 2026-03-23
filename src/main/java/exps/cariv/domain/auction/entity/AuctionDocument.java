package exps.cariv.domain.auction.entity;

import exps.cariv.domain.auction.dto.AuctionParsed;
import exps.cariv.domain.auction.dto.AuctionSnapshot;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 경락사실확인서 — Document 상속(JOINED).
 * OCR 파싱 결과를 별도 컬럼에 저장.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AuctionDocument extends Document {

    // OCR 파싱 결과 필드
    private String registrationNo;       // 차량번호
    private String chassisNo;            // 차대번호
    private String model;                // 차명
    private Integer modelYear;           // 연식
    private Long mileage;                // 주행거리
    private Integer displacement;        // 배기량
    private LocalDate initialRegistrationDate;  // 최초등록일
    private String fuel;                 // 연료
    private String color;                // 색상

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String rawJson;              // Upstage 테이블 HTML payload

    private Instant parsedAt;            // OCR 파싱 완료 시각

    public static AuctionDocument createNew(Long companyId, Long uploadedByUserId,
                                            Long vehicleId, String s3Key, String originalFilename) {
        AuctionDocument doc = AuctionDocument.builder().build();
        doc.companyId = companyId;
        doc.type = DocumentType.AUCTION_CERTIFICATE;
        doc.status = DocumentStatus.UPLOADED;
        doc.refType = DocumentRefType.VEHICLE;
        doc.refId = vehicleId;
        doc.s3Key = s3Key;
        doc.uploadedByUserId = uploadedByUserId;
        doc.originalFilename = originalFilename;
        doc.uploadedAt = Instant.now();
        return doc;
    }

    public void applyOcrResult(AuctionParsed p, String rawJson) {
        this.registrationNo = p.registrationNo();
        this.chassisNo = p.chassisNo();
        this.model = p.model();
        this.modelYear = p.modelYear();
        this.mileage = p.mileage();
        this.displacement = p.displacement();
        this.initialRegistrationDate = p.initialRegistrationDate();
        this.fuel = p.fuel();
        this.color = p.color();
        this.rawJson = rawJson;
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    public AuctionSnapshot toSnapshot() {
        return new AuctionSnapshot(
                registrationNo,
                chassisNo,
                model,
                modelYear,
                mileage,
                displacement,
                initialRegistrationDate,
                fuel,
                color
        );
    }

    /**
     * 수동 수정된 스냅샷을 엔티티에 반영한다.
     * 문자열 공백은 trim 후 저장하며 빈 문자열은 null 처리한다.
     */
    public void applyManualSnapshot(AuctionSnapshot s) {
        if (s == null) return;

        this.registrationNo = normalize(s.registrationNo());
        this.chassisNo = normalizeVinLike(s.chassisNo());
        this.model = normalize(s.model());
        this.modelYear = s.modelYear();
        this.mileage = s.mileage();
        this.displacement = s.displacement();
        this.initialRegistrationDate = s.initialRegistrationDate();
        this.fuel = normalize(s.fuel());
        this.color = normalize(s.color());
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    private static String normalize(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeVinLike(String v) {
        if (v == null) return null;
        String cleaned = v.replaceAll("\\s+", "").toUpperCase();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
