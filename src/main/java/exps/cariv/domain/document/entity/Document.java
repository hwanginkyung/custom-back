package exps.cariv.domain.document.entity;

import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@Table(
        indexes = {
                @Index(name = "idx_document_company_ref", columnList = "company_id,ref_type,ref_id"),
                @Index(name = "idx_document_company_ref_type", columnList = "company_id,ref_type,ref_id,type"),
                @Index(name = "idx_document_company_type", columnList = "company_id,type"),
                @Index(name = "idx_document_company_status", columnList = "company_id,status,createdAt")
        }
)
public abstract class Document extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    protected DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    protected DocumentStatus status;

    @Column(nullable = false, length = 400)
    protected String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 20)
    protected DocumentRefType refType;

    @Column(name = "ref_id", nullable = false)
    protected Long refId;

    @Column(nullable = false)
    protected Long uploadedByUserId;

    @Column(length = 255)
    protected String originalFilename;

    @Column(length = 100)
    protected String contentType;

    protected Long sizeBytes;

    @Column(nullable = false)
    protected Instant uploadedAt;

    @Column(length = 500)
    protected String lastError;

    /** 신규 생성 시 1회 호출 */
    public void init(
            DocumentType type,
            DocumentRefType refType,
            Long refId,
            Long uploadedByUserId,
            String s3Key,
            String originalFilename,
            String contentType,
            Long sizeBytes
    ) {
        this.type = type;
        this.refType = refType;
        this.refId = refId;
        replaceFile(uploadedByUserId, s3Key, originalFilename, contentType, sizeBytes);
    }

    /** 업로드 덮어쓰기(최신 파일로 교체) */
    public void replaceFile(
            Long uploadedByUserId,
            String s3Key,
            String originalFilename,
            String contentType,
            Long sizeBytes
    ) {
        this.uploadedByUserId = uploadedByUserId;
        this.s3Key = s3Key;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = Instant.now();
        this.lastError = null;
        this.status = DocumentStatus.UPLOADED; // 업로드만 된 상태
    }

    /** 문서를 차량에 연결 (업로드 후 차량 생성 시) */
    public void linkToVehicle(Long vehicleId) {
        this.refId = vehicleId;
    }

    public void markQueued()     { this.status = DocumentStatus.OCR_QUEUED; }
    public void markProcessing() { this.status = DocumentStatus.PROCESSING; }
    public void markOcrDraft()   { this.status = DocumentStatus.OCR_DRAFT; }
    public void markConfirmed()  { this.status = DocumentStatus.CONFIRMED; }
    public void markFailed(String error) { this.status = DocumentStatus.FAILED; this.lastError = error; }
}
