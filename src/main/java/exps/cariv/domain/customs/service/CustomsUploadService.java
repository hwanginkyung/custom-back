package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.repository.CustomsRequestRepository;
import exps.cariv.domain.customs.repository.CustomsRequestVehicleRepository;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.export.entity.ExportCertificateDocument;
import exps.cariv.domain.export.repository.ExportCertificateDocumentRepository;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrQueueService;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.aws.S3Upload.UploadResult;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

/**
 * 수출신고필증 문서 업로드 서비스.
 * <p>업로드 → S3 저장 → OCR Job 생성 → Redis 큐 → OcrJobWorker → ExportOcrService</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomsUploadService {

    private final CustomsRequestRepository requestRepo;
    private final CustomsRequestVehicleRepository requestVehicleRepo;
    private final ExportCertificateDocumentRepository exportDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrQueueService ocrQueueService;
    private final S3Upload s3Upload;
    private final PlatformTransactionManager txManager;

    public record UploadResponse(Long documentId, String s3Key, Long jobId) {}
    public record AssetUploadResponse(String s3Key, String originalFilename, String contentType, long sizeBytes) {}

    /**
     * 수출신고필증 업로드 → S3 → OCR Job.
     */
    public UploadResponse uploadExportCertificate(Long companyId, Long userId, MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Long documentId = Objects.requireNonNull(
                tx.execute(status -> createPendingDocument(companyId, userId, file)),
                "failed to create export certificate document"
        );

        // 외부 I/O는 트랜잭션 밖에서 실행
        UploadResult up = s3Upload.uploadRawDocument(companyId, documentId, file);

        UploadResponse response = Objects.requireNonNull(
                tx.execute(status -> finalizeUploadAndCreateJob(companyId, userId, documentId, up)),
                "failed to finalize export certificate upload"
        );

        // Redis enqueue도 트랜잭션 밖에서 실행
        ocrQueueService.enqueue(response.jobId());
        log.info("[CustomsUpload] export certificate uploaded docId={} jobId={}",
                response.documentId(), response.jobId());
        return response;
    }

    private Long createPendingDocument(Long companyId, Long userId, MultipartFile file) {
        // 수출신고필증 업로드는 vehicleId를 미리 받지 않고, OCR 결과(차대번호)로 차량을 매칭한다.
        ExportCertificateDocument doc = ExportCertificateDocument.createNew(
                companyId, userId, 0L, "__PENDING__", file.getOriginalFilename()
        );
        doc = exportDocRepo.saveAndFlush(doc);
        return doc.getId();
    }

    private UploadResponse finalizeUploadAndCreateJob(Long companyId,
                                                      Long userId,
                                                      Long documentId,
                                                      UploadResult up) {
        ExportCertificateDocument doc = exportDocRepo.findById(documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "수출신고필증 문서를 찾을 수 없습니다."));
        if (!doc.getCompanyId().equals(companyId)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }

        doc.replaceFile(userId, up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
        doc = exportDocRepo.save(doc);

        // OCR Job 생성
        OcrParseJob job = OcrParseJob.builder()
                .documentType(DocumentType.EXPORT_CERTIFICATE)
                .status(OcrJobStatus.QUEUED)
                .vehicleId(0L)
                .vehicleDocumentId(doc.getId())
                .requestedByUserId(userId)
                .s3KeySnapshot(doc.getS3Key())
                .build();
        job.setCompanyId(companyId);
        job = jobRepo.save(job);

        return new UploadResponse(doc.getId(), doc.getS3Key(), job.getId());
    }

    /**
     * 신고필증(통관요청) 첨부 사진 업로드.
     * category:
     * - VEHICLE: 차량별 사진 (vehicleId 필수)
     * - CONTAINER: 요청 공통 컨테이너 사진
     * <p>업로드 시점에는 최종 submit 데이터가 아직 없을 수 있으므로 카테고리/vehicleId 형식만 검증한다.</p>
     */
    public AssetUploadResponse uploadAsset(Long companyId,
                                           Long requestId,
                                           String category,
                                           Long vehicleId,
                                           MultipartFile file) {
        requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "통관 요청을 찾을 수 없습니다."));

        String normalized = category == null ? "" : category.trim().toUpperCase();
        String folder;

        switch (normalized) {
            case "VEHICLE" -> {
                if (vehicleId == null) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "VEHICLE 카테고리에는 vehicleId가 필요합니다.");
                }
                folder = "vehicle-" + vehicleId;
            }
            case "CONTAINER" -> {
                folder = "container";
            }
            default -> throw new CustomException(ErrorCode.INVALID_INPUT, "category는 VEHICLE 또는 CONTAINER 이어야 합니다.");
        }

        UploadResult up = s3Upload.uploadCustomsPhoto(companyId, requestId, folder, file);
        return new AssetUploadResponse(up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
    }

    /**
     * 신고필증(통관요청) 첨부 사진 삭제.
     * <p>request/category/vehicle 범위를 검증한 뒤 S3 파일을 삭제하고,
     * 이미 요청 데이터에 반영된 key라면 DB 슬롯에서도 함께 제거한다.</p>
     */
    @Transactional
    public void deleteAsset(Long companyId,
                            Long requestId,
                            String category,
                            Long vehicleId,
                            String s3Key) {
        var request = requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "통관 요청을 찾을 수 없습니다."));

        if (s3Key == null || s3Key.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "삭제할 s3Key가 필요합니다.");
        }
        String trimmedKey = s3Key.trim();

        String normalized = category == null ? "" : category.trim().toUpperCase();
        String basePrefix = "customs-requests/" + companyId + "/" + requestId + "/";
        if (!trimmedKey.startsWith(basePrefix)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "요청 범위를 벗어난 첨부파일 키입니다.");
        }

        switch (normalized) {
            case "VEHICLE" -> {
                if (vehicleId == null) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "VEHICLE 카테고리에는 vehicleId가 필요합니다.");
                }
                String expectedPrefix = basePrefix + "vehicle-" + vehicleId + "/";
                if (!trimmedKey.startsWith(expectedPrefix)) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "VEHICLE 카테고리 경로와 s3Key가 일치하지 않습니다.");
                }

                requestVehicleRepo.findByCustomsRequestIdAndVehicleId(requestId, vehicleId)
                        .ifPresent(item -> item.removeVehiclePhotoKey(trimmedKey));
            }
            case "CONTAINER" -> {
                String expectedPrefix = basePrefix + "container/";
                if (!trimmedKey.startsWith(expectedPrefix)) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "CONTAINER 카테고리 경로와 s3Key가 일치하지 않습니다.");
                }
                request.removeContainerPhotoKey(trimmedKey);
            }
            default -> throw new CustomException(ErrorCode.INVALID_INPUT, "category는 VEHICLE 또는 CONTAINER 이어야 합니다.");
        }

        s3Upload.deleteByKey(trimmedKey);
    }
}
