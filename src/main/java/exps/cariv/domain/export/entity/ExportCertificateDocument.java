package exps.cariv.domain.export.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import jakarta.persistence.Entity;
import lombok.*;

import java.time.Instant;

/**
 * 수출신고필증 문서 — Document 상속(JOINED).
 * OCR 파싱 결과는 별도 Export 엔티티에 저장.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExportCertificateDocument extends Document {

    public static ExportCertificateDocument createNew(Long companyId, Long uploadedByUserId,
                                                       Long vehicleId, String s3Key, String originalFilename) {
        ExportCertificateDocument doc = new ExportCertificateDocument();
        doc.companyId = companyId;
        doc.type = DocumentType.EXPORT_CERTIFICATE;
        doc.status = DocumentStatus.UPLOADED;
        doc.refType = DocumentRefType.VEHICLE;
        doc.refId = vehicleId;
        doc.s3Key = s3Key;
        doc.uploadedByUserId = uploadedByUserId;
        doc.originalFilename = originalFilename;
        doc.uploadedAt = Instant.now();
        return doc;
    }
}
