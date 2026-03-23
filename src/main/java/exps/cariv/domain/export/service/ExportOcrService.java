package exps.cariv.domain.export.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.export.dto.ExportParseResult;
import exps.cariv.domain.export.dto.ExportParseResult.ExportInfo;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.export.entity.ExportCertificateDocument;
import exps.cariv.domain.export.repository.ExportCertificateDocumentRepository;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import exps.cariv.domain.upstage.service.UpstageService;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.aws.S3ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수출신고필증 OCR 처리 서비스.
 * <p>OcrJobWorker 에서 documentType == EXPORT_CERTIFICATE 일 때 호출.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExportOcrService {

    private final UpstageService upstageService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final ExportParserService parser;
    private final ExportCertificateDocumentRepository exportDocRepo;
    private final VehicleRepository vehicleRepo;
    private final NotificationCommandService notificationCommandService;

    @Transactional
    public void processJob(Long companyId, OcrParseJob job) {
        // 1) S3 → bytes
        byte[] bytes = s3Reader.readBytes(job.getS3KeySnapshot());
        if (bytes == null) {
            throw new IllegalStateException("S3 object not found: key=" + job.getS3KeySnapshot());
        }
        Resource resource = new ByteArrayResource(bytes);

        // 2) Upstage document-parse
        ExportCertificateDocument doc = exportDocRepo.findById(job.getVehicleDocumentId())
                .filter(d -> d.getCompanyId().equals(companyId))
                .orElseThrow(() -> new IllegalStateException(
                        "ExportCertificateDocument not found id=" + job.getVehicleDocumentId()));

        String upstageJson;
        UpstageResponse res;
        try {
            upstageJson = upstageService.parseDocuments(companyId, resource, doc.getOriginalFilename());
            res = mapper.readValue(upstageJson, UpstageResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Upstage OCR 호출 실패: " + e.getMessage(), e);
        }

        // 3) 파싱
        ExportParseResult parsed = parser.parse(res);
        ExportInfo info = parsed.info();

        // 4) OCR 결과(차대번호)로 차량 자동 매칭
        Long vehicleId = resolveVehicleId(companyId, info);
        vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new IllegalStateException("Vehicle not found id=" + vehicleId));
        job.updateVehicleId(vehicleId);

        // 기존 해당 차량의 신고필증 문서가 있으면 최신 문서(doc)로 교체 연결
        exportDocRepo.findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                        companyId, DocumentRefType.VEHICLE, vehicleId
                ).stream()
                .filter(existing -> !existing.getId().equals(doc.getId()))
                .forEach(existing -> {
                    existing.linkToVehicle(0L);
                    exportDocRepo.save(existing);
                });
        doc.linkToVehicle(vehicleId);
        exportDocRepo.save(doc);

        // 5) Job 성공
        try {
            job.markSucceeded(mapper.writeValueAsString(parsed));
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        // 6) 알림
        String msg = parsed.missingFields().isEmpty()
                ? "수출신고필증 OCR 완료"
                : "수출신고필증 OCR 완료 (누락: " + String.join(", ", parsed.missingFields()) + ")";
        notificationCommandService.createOcr(companyId, job.getRequestedByUserId(),
                NotificationType.OCR_COMPLETE, DocumentType.EXPORT_CERTIFICATE,
                job.getVehicleId(), job.getId(),
                "수출신고필증 OCR 완료", msg);
    }

    private Long resolveVehicleId(Long companyId, ExportInfo info) {
        String vin = normalizeVin(info.chassisNo());
        if (vin == null) {
            throw new IllegalStateException("OCR 차량 매칭 실패: 차대번호(chassisNo)가 비어 있습니다.");
        }

        return vehicleRepo.findByCompanyIdAndVinAndDeletedFalse(companyId, vin)
                .map(Vehicle::getId)
                .orElseThrow(() -> new IllegalStateException("OCR 차량 매칭 실패: vin=" + vin));
    }

    private String normalizeVin(String vin) {
        if (vin == null) return null;
        String normalized = vin.replaceAll("\\s+", "").toUpperCase();
        return normalized.isBlank() ? null : normalized;
    }
}
