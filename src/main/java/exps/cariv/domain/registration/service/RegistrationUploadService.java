package exps.cariv.domain.registration.service;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrQueueService;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.aws.S3Upload.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RegistrationUploadService {

    private final RegistrationDocumentRepository regDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrQueueService ocrQueueService;
    private final S3Upload s3Upload;
    private final PlatformTransactionManager txManager;

    /**
     * 차량등록증 업로드(덮어쓰기) + OCR Job(QUEUE) 생성.
     * - 문서 row는 (companyId, VEHICLE, vehicleId, REGISTRATION) 1개 유지
     * - S3 key는 docId 기반으로 생성
     */
    public EnqueueResult uploadAndEnqueue(Long companyId,
                                         Long userId,
                                         Long vehicleId,
                                         MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Long docId = Objects.requireNonNull(
                tx.execute(status -> ensureDocument(companyId, userId, vehicleId, file)),
                "failed to create or load registration document"
        );

        // 외부 I/O는 트랜잭션 밖에서 실행한다.
        UploadResult up = s3Upload.uploadRawDocument(companyId, docId, file);

        EnqueueResult result = Objects.requireNonNull(
                tx.execute(status -> finalizeUploadAndCreateJob(companyId, userId, vehicleId, docId, up)),
                "failed to finalize registration upload"
        );

        // Redis enqueue도 트랜잭션 밖에서 실행한다.
        ocrQueueService.enqueue(result.jobId());
        return result;
    }

    private Long ensureDocument(Long companyId, Long userId, Long vehicleId, MultipartFile file) {
        // 1) 문서 row 확보(락) + (없으면 생성)
        RegistrationDocument doc = regDocRepo.findForUpdate(companyId, DocumentRefType.VEHICLE, vehicleId, DocumentType.REGISTRATION)
                .orElseGet(() -> {
                    RegistrationDocument created = RegistrationDocument.createNew(
                            companyId, userId, vehicleId, "__PENDING__", file.getOriginalFilename());
                    return regDocRepo.saveAndFlush(created);
                });
        return doc.getId();
    }

    private EnqueueResult finalizeUploadAndCreateJob(Long companyId,
                                                     Long userId,
                                                     Long vehicleId,
                                                     Long docId,
                                                     UploadResult up) {
        RegistrationDocument doc = regDocRepo.findByCompanyIdAndId(companyId, docId)
                .orElseThrow(() -> new IllegalStateException("RegistrationDocument not found id=" + docId));

        // 2) 파일 메타 교체 + 저장
        doc.replaceFile(userId, up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
        doc = regDocRepo.save(doc);

        // 3) OCR Job 생성(비동기 처리)
        OcrParseJob job = OcrParseJob.builder()
                .documentType(DocumentType.REGISTRATION)
                .status(OcrJobStatus.QUEUED)
                .vehicleId(vehicleId)
                .vehicleDocumentId(doc.getId())
                .requestedByUserId(userId)
                .s3KeySnapshot(doc.getS3Key())
                .build();
        job.setCompanyId(companyId);
        job = jobRepo.save(job);

        return new EnqueueResult(doc.getId(), job.getId());
    }

    public record EnqueueResult(Long registrationDocumentId, Long jobId) {}
}
