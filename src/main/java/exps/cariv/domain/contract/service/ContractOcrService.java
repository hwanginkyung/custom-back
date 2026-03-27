package exps.cariv.domain.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.contract.dto.ContractParseResult;
import exps.cariv.domain.contract.dto.ContractParsed;
import exps.cariv.domain.contract.entity.ContractDocument;
import exps.cariv.domain.contract.repository.ContractDocumentRepository;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import exps.cariv.domain.upstage.service.DocumentTypeDetector;
import exps.cariv.domain.upstage.service.UpstageService;
import exps.cariv.domain.upstage.service.UpstageTablePayloadBuilder;
import exps.cariv.global.aws.S3ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매매계약서 OCR 처리 서비스.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractOcrService {

    private final UpstageService upstageService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final ContractParserService parser;
    private final ContractLlmRefiner contractLlmRefiner;
    private final ContractDocumentRepository contractDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final NotificationCommandService notificationCommandService;
    private final PlatformTransactionManager txManager;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processJob(Long companyId, OcrParseJob job) {
        ContractDocument doc = contractDocRepo.findByCompanyIdAndId(companyId, job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException("ContractDocument not found id=" + job.getVehicleDocumentId()));

        byte[] bytes = s3Reader.readBytes(job.getS3KeySnapshot());
        if (bytes == null) {
            throw new IllegalStateException("S3 object not found: key=" + job.getS3KeySnapshot());
        }
        Resource resource = new ByteArrayResource(bytes);

        String upstageJson;
        UpstageResponse res;
        try {
            upstageJson = upstageService.parseDocuments(companyId, resource, doc.getOriginalFilename());
            res = mapper.readValue(upstageJson, UpstageResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Upstage OCR 호출 실패: " + e.getMessage(), e);
        }

        // 문서 타입 검증
        DocumentType detected = DocumentTypeDetector.detect(res);
        if (detected != DocumentType.CONTRACT) {
            throw new IllegalStateException("매매계약서가 아닙니다. detected=" + detected);
        }

        ContractParseResult rawParsed = parser.parseAndValidate(res);
        // LLM 보정 적용
        ContractParsed refined = contractLlmRefiner.refineIfNeeded(rawParsed.parsed(), res);
        ContractParseResult parsed = new ContractParseResult(refined, rawParsed.missingFields(), rawParsed.errorFields());
        String resultJson;
        try {
            resultJson = mapper.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        String tableHtmlJson;
        try {
            tableHtmlJson = UpstageTablePayloadBuilder.buildTableHtmlJson(mapper, res);
        } catch (Exception e) {
            throw new IllegalStateException("테이블 파싱 실패: " + e.getMessage(), e);
        }

        String msg = parsed.missingFields().isEmpty()
                ? "매매계약서 OCR 완료"
                : "매매계약서 OCR 완료 (누락: " + String.join(", ", parsed.missingFields()) + ")";

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> persistSuccess(
                companyId,
                job.getId(),
                job.getVehicleDocumentId(),
                job.getVehicleId(),
                job.getRequestedByUserId(),
                tableHtmlJson,
                parsed,
                resultJson,
                msg
        ));
    }

    private void persistSuccess(Long companyId,
                                Long jobId,
                                Long vehicleDocumentId,
                                Long vehicleId,
                                Long requestedByUserId,
                                String tableHtmlJson,
                                ContractParseResult parsed,
                                String resultJson,
                                String notificationMessage) {
        ContractDocument doc = contractDocRepo.findByCompanyIdAndId(companyId, vehicleDocumentId)
                .orElseThrow(() -> new IllegalStateException("ContractDocument not found id=" + vehicleDocumentId));
        doc.applyOcrResult(parsed.parsed(), tableHtmlJson);
        contractDocRepo.save(doc);

        OcrParseJob persistedJob = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new IllegalStateException("OcrParseJob not found id=" + jobId));
        persistedJob.markSucceeded(resultJson);
        jobRepo.save(persistedJob);

        notificationCommandService.createOcr(companyId, requestedByUserId,
                NotificationType.OCR_COMPLETE, DocumentType.CONTRACT,
                vehicleId, jobId,
                "매매계약서 OCR 완료", notificationMessage);
    }
}
