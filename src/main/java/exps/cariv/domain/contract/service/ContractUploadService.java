package exps.cariv.domain.contract.service;

import exps.cariv.domain.contract.entity.ContractDocument;
import exps.cariv.domain.contract.repository.ContractDocumentRepository;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrQueueService;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.aws.S3Upload.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

/**
 * 매매계약서 업로드 서비스 (Command).
 *
 * S3 업로드와 Redis enqueue는 트랜잭션 밖에서 실행한다.
 * 패턴: TX1(문서 생성) → S3 업로드 → TX2(메타 갱신 + OCR Job) → Redis enqueue
 */
@Service
@RequiredArgsConstructor
public class ContractUploadService {

    private final ContractDocumentRepository contractDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrQueueService ocrQueueService;
    private final S3Upload s3Upload;
    private final PlatformTransactionManager txManager;

    public EnqueueResult uploadAndEnqueue(Long companyId,
                                          Long userId,
                                          Long vehicleId,
                                          MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // 1) TX: 문서 row 생성
        Long docId = Objects.requireNonNull(
                tx.execute(status -> {
                    ContractDocument doc = ContractDocument.createNew(
                            companyId, userId, vehicleId, "__PENDING__", file.getOriginalFilename());
                    return contractDocRepo.saveAndFlush(doc).getId();
                }),
                "failed to create contract document"
        );

        // 2) 트랜잭션 밖: S3 업로드
        UploadResult up = s3Upload.uploadRawDocument(companyId, docId, file);

        // 3) TX: 파일 메타 업데이트 + OCR Job 생성
        EnqueueResult result = Objects.requireNonNull(
                tx.execute(status -> {
                    ContractDocument doc = contractDocRepo.findByCompanyIdAndId(companyId, docId)
                            .orElseThrow(() -> new IllegalStateException("ContractDocument not found id=" + docId));
                    doc.replaceFile(userId, up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
                    contractDocRepo.save(doc);

                    Long jobId = createOcrJob(companyId, userId, doc.getId(), doc.getS3Key());
                    return new EnqueueResult(doc.getId(), doc.getS3Key(), jobId);
                }),
                "failed to finalize contract upload"
        );

        // 4) 트랜잭션 밖: Redis enqueue
        ocrQueueService.enqueue(result.jobId());
        return result;
    }

    private Long createOcrJob(Long companyId, Long userId, Long documentId, String s3Key) {
        OcrParseJob job = OcrParseJob.builder()
                .documentType(DocumentType.CONTRACT)
                .status(OcrJobStatus.QUEUED)
                .vehicleId(0L)
                .vehicleDocumentId(documentId)
                .requestedByUserId(userId)
                .s3KeySnapshot(s3Key)
                .build();
        job.setCompanyId(companyId);
        job = jobRepo.save(job);
        return job.getId();
    }

    public record EnqueueResult(Long contractDocumentId, String s3Key, Long jobId) {}
}
