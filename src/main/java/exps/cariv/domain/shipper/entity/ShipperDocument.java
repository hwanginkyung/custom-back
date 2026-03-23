package exps.cariv.domain.shipper.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 화주 관련 문서 (신분증, 사업자등록증, 사인방).
 * OCR 불필요, 단순 파일 업로드.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DiscriminatorValue("SHIPPER_DOC")
@Table(name = "shipper_document")
public class ShipperDocument extends Document {

    public static ShipperDocument createNew(Long companyId, Long uploadedByUserId,
                                             Long shipperId, DocumentType type,
                                             String s3Key, String originalFilename,
                                             String contentType, long sizeBytes) {
        ShipperDocument doc = new ShipperDocument();
        doc.companyId = companyId;
        doc.type = type;
        doc.status = DocumentStatus.CONFIRMED;  // OCR 없으므로 바로 확정
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
}
