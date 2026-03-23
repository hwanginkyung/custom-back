package exps.cariv.domain.shipper.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.shipper.dto.IdCardSnapshot;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 신분증 문서 엔티티 (OCR 대상, 내부 저장용).
 * 주민등록증 또는 운전면허증.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DiscriminatorValue("ID_CARD")
@Table(name = "id_card_document")
public class IdCardDocument extends Document {

    private String holderName;      // 이름
    private String idNumber;        // 주민등록번호 (마스킹 저장 가능)
    private String idAddress;       // 주소
    private String issueDate;       // 발급일

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String rawJson;

    private Instant parsedAt;

    public static IdCardDocument createNew(Long companyId, Long uploadedByUserId,
                                            Long shipperId, String s3Key, String originalFilename,
                                            String contentType, long sizeBytes) {
        IdCardDocument doc = new IdCardDocument();
        doc.companyId = companyId;
        doc.type = DocumentType.ID_CARD;
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

    public void applyOcrResult(String holderName, String idNumber,
                                String idAddress, String issueDate, String rawJson) {
        this.holderName = holderName;
        this.idNumber = idNumber;
        this.idAddress = idAddress;
        this.issueDate = issueDate;
        this.rawJson = rawJson;
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    public IdCardSnapshot toSnapshot() {
        return new IdCardSnapshot(
                holderName,
                idNumber,
                idAddress,
                issueDate
        );
    }

    public void applyManualSnapshot(IdCardSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.holderName = normalize(snapshot.holderName());
        this.idNumber = normalize(snapshot.idNumber());
        this.idAddress = normalize(snapshot.idAddress());
        this.issueDate = normalize(snapshot.issueDate());
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
