package exps.cariv.domain.shipper.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.shipper.dto.BizRegSnapshot;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사업자등록증 문서 엔티티 (OCR 대상, 내부 저장용).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DiscriminatorValue("BIZ_REGISTRATION")
@Table(name = "biz_reg_document")
public class BizRegDocument extends Document {

    private String companyName;         // 상호(법인명)
    private String representativeName;  // 대표자
    private String bizNumber;           // 사업자등록번호
    private String bizType;             // 업태
    private String bizCategory;         // 종목
    private String bizAddress;          // 사업장 소재지
    private String openDate;            // 개업 연월일

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String rawJson;

    private Instant parsedAt;

    public static BizRegDocument createNew(Long companyId, Long uploadedByUserId,
                                            Long shipperId, String s3Key, String originalFilename,
                                            String contentType, long sizeBytes) {
        BizRegDocument doc = new BizRegDocument();
        doc.companyId = companyId;
        doc.type = DocumentType.BIZ_REGISTRATION;
        doc.status = DocumentStatus.UPLOADED;
        doc.refType = DocumentRefType.SHIPPER;
        doc.refId = shipperId;
        doc.s3Key = s3Key;
        doc.uploadedByUserId = uploadedByUserId;
        doc.originalFilename = originalFilename;
        doc.contentType = contentType;
        doc.sizeBytes = sizeBytes;
        doc.uploadedAt = Instant.now();
        return doc;
    }

    public void applyOcrResult(String companyName, String representativeName,
                                String bizNumber, String bizType, String bizCategory,
                                String bizAddress, String openDate, String rawJson) {
        this.companyName = companyName;
        this.representativeName = representativeName;
        this.bizNumber = bizNumber;
        this.bizType = bizType;
        this.bizCategory = bizCategory;
        this.bizAddress = bizAddress;
        this.openDate = openDate;
        this.rawJson = rawJson;
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    public BizRegSnapshot toSnapshot() {
        return new BizRegSnapshot(
                companyName,
                representativeName,
                bizNumber,
                bizType,
                bizCategory,
                bizAddress,
                openDate
        );
    }

    public void applyManualSnapshot(BizRegSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.companyName = normalize(snapshot.companyName());
        this.representativeName = normalize(snapshot.representativeName());
        this.bizNumber = normalize(snapshot.bizNumber());
        this.bizType = normalize(snapshot.bizType());
        this.bizCategory = normalize(snapshot.bizCategory());
        this.bizAddress = normalize(snapshot.bizAddress());
        this.openDate = normalize(snapshot.openDate());
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
