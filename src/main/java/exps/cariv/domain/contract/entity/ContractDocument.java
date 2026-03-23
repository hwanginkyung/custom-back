package exps.cariv.domain.contract.entity;

import exps.cariv.domain.contract.dto.ContractParsed;
import exps.cariv.domain.contract.dto.ContractSnapshot;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 매매계약서 — Document 상속(JOINED).
 * OCR 파싱 결과를 별도 컬럼에 저장.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ContractDocument extends Document {

    // OCR 파싱 결과 필드
    private String registrationNo;       // 차량번호
    private String vehicleType;          // 차종
    private String model;                // 차명
    private String chassisNo;            // 차대번호

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String rawJson;

    private Instant parsedAt;

    public static ContractDocument createNew(Long companyId, Long uploadedByUserId,
                                             Long vehicleId, String s3Key, String originalFilename) {
        ContractDocument doc = ContractDocument.builder().build();
        doc.companyId = companyId;
        doc.type = DocumentType.CONTRACT;
        doc.status = DocumentStatus.UPLOADED;
        doc.refType = DocumentRefType.VEHICLE;
        doc.refId = vehicleId;
        doc.s3Key = s3Key;
        doc.uploadedByUserId = uploadedByUserId;
        doc.originalFilename = originalFilename;
        doc.uploadedAt = Instant.now();
        return doc;
    }

    public void applyOcrResult(ContractParsed p, String rawJson) {
        this.registrationNo = p.registrationNo();
        this.vehicleType = p.vehicleType();
        this.model = p.model();
        this.chassisNo = p.chassisNo();
        this.rawJson = rawJson;
        this.parsedAt = Instant.now();
        markOcrDraft();
    }

    public ContractSnapshot toSnapshot() {
        return new ContractSnapshot(
                registrationNo,
                vehicleType,
                model,
                chassisNo
        );
    }

    /**
     * 수동 수정된 스냅샷을 엔티티에 반영한다.
     * 문자열 공백은 trim 후 저장하며 빈 문자열은 null 처리한다.
     */
    public void applyManualSnapshot(ContractSnapshot s) {
        if (s == null) return;

        this.registrationNo = normalize(s.registrationNo());
        this.vehicleType = normalize(s.vehicleType());
        this.model = normalize(s.model());
        this.chassisNo = normalizeVinLike(s.chassisNo());
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
