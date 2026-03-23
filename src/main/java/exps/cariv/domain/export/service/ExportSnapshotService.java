package exps.cariv.domain.export.service;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.export.dto.ExportSnapshot;
import exps.cariv.domain.export.dto.request.ExportSnapshotUpdateRequest;
import exps.cariv.domain.export.dto.response.ExportDocumentResponse;
import exps.cariv.domain.export.entity.Export;
import exps.cariv.domain.export.entity.ExportCertificateDocument;
import exps.cariv.domain.export.repository.ExportCertificateDocumentRepository;
import exps.cariv.domain.export.repository.ExportRepository;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 수출신고필증 OCR 스냅샷 조회/수정 서비스.
 */
@Service
@RequiredArgsConstructor
public class ExportSnapshotService {

    private final ExportCertificateDocumentRepository exportDocRepo;
    private final ExportRepository exportRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrResultNormalizer ocrResultNormalizer;
    private final VehicleRepository vehicleRepo;

    @Transactional(readOnly = true)
    public ExportDocumentResponse getSnapshot(Long companyId, Long documentId) {
        ExportCertificateDocument doc = exportDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        var successJob = jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                companyId, doc.getId(), OcrJobStatus.SUCCEEDED
        );
        OcrFieldResult ocrResult = successJob
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(OcrFieldResult::empty);
        Instant parsedAt = successJob.map(OcrParseJob::getFinishedAt).orElse(null);

        ExportSnapshot snapshot = null;
        Long vehicleId = doc.getRefId();
        if (vehicleId != null && vehicleId > 0) {
            snapshot = exportRepo.findFirstByCompanyIdAndVehicleIdOrderByCreatedAtDesc(companyId, vehicleId)
                    .map(this::toSnapshot)
                    .orElse(null);
        }
        if (snapshot == null) {
            snapshot = toSnapshotFromOcrValues(ocrResult.values());
        }

        return new ExportDocumentResponse(
                doc.getId(),
                doc.getS3Key(),
                doc.getOriginalFilename(),
                doc.getUploadedAt(),
                parsedAt,
                snapshot,
                ocrResult
        );
    }

    @Transactional(readOnly = true)
    public ExportDocumentResponse getSnapshotByJobId(Long companyId, Long jobId) {
        OcrParseJob job = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (job.getDocumentType() != DocumentType.EXPORT_CERTIFICATE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "수출신고필증 OCR 작업(jobId)이 아닙니다.");
        }
        return getSnapshot(companyId, job.getVehicleDocumentId());
    }

    @Transactional
    public void updateSnapshot(Long companyId, Long documentId, ExportSnapshotUpdateRequest req) {
        ExportCertificateDocument doc = exportDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        Long vehicleId = doc.getRefId();
        if (vehicleId == null || vehicleId <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "차량 매칭 전 문서는 수정할 수 없습니다.");
        }

        ExportSnapshot base = getSnapshot(companyId, documentId).snapshot();
        ExportSnapshot merged = merge(base, req);

        Export export = exportRepo.findFirstByCompanyIdAndVehicleIdOrderByCreatedAtDesc(companyId, vehicleId)
                .orElseGet(() -> {
                    Export created = Export.builder()
                            .vehicleId(vehicleId)
                            .build();
                    created.setCompanyId(companyId);
                    return created;
                });

        export.updateDeclarationNo(merged.declarationNo());
        export.updateDeclarationDate(merged.declarationDate());
        export.updateAcceptanceDate(merged.acceptanceDate());
        export.updateIssueNo(merged.issueNo());
        export.updateDestCountryCode(merged.destCountryCode());
        export.updateDestCountryName(merged.destCountryName());
        export.updateLoadingPortCode(merged.loadingPortCode());
        export.updateLoadingPortName(merged.loadingPortName());
        export.updateContainerNo(merged.containerNo());
        export.updateItemName(merged.itemName());
        export.updateModelYear(merged.modelYear());
        export.updateChassisNo(merged.chassisNo());
        export.updateAmountKrw(merged.amountKrw());
        export.updateLoadingDeadline(merged.loadingDeadline());
        export.updateBuyerName(merged.buyerName());
        exportRepo.save(export);

        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (vehicle.getStage() == VehicleStage.BEFORE_CERTIFICATE) {
            vehicle.updateStage(VehicleStage.COMPLETED);
            vehicleRepo.save(vehicle);
        }

        doc.markConfirmed();
        exportDocRepo.save(doc);
    }

    private ExportSnapshot toSnapshot(Export e) {
        return new ExportSnapshot(
                e.getDeclarationNo(),
                e.getDeclarationDate(),
                e.getAcceptanceDate(),
                e.getIssueNo(),
                e.getDestCountryCode(),
                e.getDestCountryName(),
                e.getLoadingPortCode(),
                e.getLoadingPortName(),
                e.getContainerNo(),
                e.getItemName(),
                e.getModelYear(),
                e.getChassisNo(),
                e.getAmountKrw(),
                e.getLoadingDeadline(),
                e.getBuyerName()
        );
    }

    private ExportSnapshot merge(ExportSnapshot base, ExportSnapshotUpdateRequest req) {
        ExportSnapshot safeBase = base == null ? emptySnapshot() : base;
        return new ExportSnapshot(
                firstNonBlank(req.declarationNo(), safeBase.declarationNo()),
                firstNonNull(req.declarationDate(), safeBase.declarationDate()),
                firstNonNull(req.acceptanceDate(), safeBase.acceptanceDate()),
                firstNonBlank(req.issueNo(), safeBase.issueNo()),
                firstNonBlank(req.destCountryCode(), safeBase.destCountryCode()),
                firstNonBlank(req.destCountryName(), safeBase.destCountryName()),
                firstNonBlank(req.loadingPortCode(), safeBase.loadingPortCode()),
                firstNonBlank(req.loadingPortName(), safeBase.loadingPortName()),
                firstNonBlank(req.containerNo(), safeBase.containerNo()),
                firstNonBlank(req.itemName(), safeBase.itemName()),
                firstNonBlank(req.modelYear(), safeBase.modelYear()),
                firstNonBlank(req.chassisNo(), safeBase.chassisNo()),
                firstNonNull(req.amountKrw(), safeBase.amountKrw()),
                firstNonNull(req.loadingDeadline(), safeBase.loadingDeadline()),
                firstNonBlank(req.buyerName(), safeBase.buyerName())
        );
    }

    private ExportSnapshot emptySnapshot() {
        return new ExportSnapshot(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }

    private ExportSnapshot toSnapshotFromOcrValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new ExportSnapshot(
                values.get("declarationNo"),
                parseDate(values.get("declarationDate")),
                parseDate(values.get("acceptanceDate")),
                values.get("issueNo"),
                values.get("destCountryCode"),
                values.get("destCountryName"),
                values.get("loadingPortCode"),
                values.get("loadingPortName"),
                values.get("containerNo"),
                values.get("itemName"),
                values.get("modelYear"),
                values.get("chassisNo"),
                parseLong(values.get("amountKrw")),
                parseDate(values.get("loadingDeadline")),
                values.get("buyerName")
        );
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("[^0-9-]", "");
        if (digits.isBlank() || "-".equals(digits)) return null;
        try {
            return Long.parseLong(digits);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
