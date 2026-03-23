package exps.cariv.domain.ocr.entity;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.global.common.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(
        indexes = {
                @Index(name="idx_job_company_status", columnList="company_id,status,createdAt")
        }
)
public class OcrParseJob extends TenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=30)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=20)
    private OcrJobStatus status;

    @Column(nullable=false)
    private Long vehicleId;

    @Column(nullable=false)
    private Long vehicleDocumentId;

    @Column(nullable=false)
    private Long requestedByUserId;

    @Column(nullable=false)
    private String s3KeySnapshot;

    private Instant startedAt;
    private Instant finishedAt;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition="LONGTEXT")
    private String resultJson; // optional(디버그/응답용). 싫으면 제거 가능

    private String errorMessage;

    public void updateVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }

    public void markProcessing() { this.status = OcrJobStatus.PROCESSING; this.startedAt = Instant.now(); }
    public void markSucceeded(String resultJson) { this.status = OcrJobStatus.SUCCEEDED; this.finishedAt = Instant.now(); this.resultJson = resultJson; }
    public void markFailed(String errorMessage) { this.status = OcrJobStatus.FAILED; this.finishedAt = Instant.now(); this.errorMessage = errorMessage; }
}
