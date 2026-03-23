package exps.cariv.domain.auction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.auction.dto.AuctionParseResult;
import exps.cariv.domain.auction.entity.AuctionDocument;
import exps.cariv.domain.auction.repository.AuctionDocumentRepository;
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
 * 경락사실확인서 OCR 처리 서비스.
 * RegistrationOcrService와 동일한 패턴.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionOcrService {

    private final UpstageService upstageService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final AuctionParserService parser;
    private final AuctionDocumentRepository auctionDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final NotificationCommandService notificationCommandService;
    private final PlatformTransactionManager txManager;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processJob(Long companyId, OcrParseJob job) {
        AuctionDocument doc = auctionDocRepo.findByCompanyIdAndId(companyId, job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException("AuctionDocument not found id=" + job.getVehicleDocumentId()));

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
        if (detected != DocumentType.AUCTION_CERTIFICATE) {
            throw new IllegalStateException("경락사실확인서가 아닙니다. detected=" + detected);
        }

        // 파싱
        AuctionParseResult parsed = parser.parseAndValidate(res);
        String resultJson;
        try {
            resultJson = mapper.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        // 결과 저장
        String tableHtmlJson;
        try {
            tableHtmlJson = UpstageTablePayloadBuilder.buildTableHtmlJson(mapper, res);
        } catch (Exception e) {
            throw new IllegalStateException("테이블 파싱 실패: " + e.getMessage(), e);
        }

        // 성공 알림
        String msg = parsed.missingFields().isEmpty()
                ? "경락사실확인서 OCR 완료"
                : "경락사실확인서 OCR 완료 (누락: " + String.join(", ", parsed.missingFields()) + ")";

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
                                AuctionParseResult parsed,
                                String resultJson,
                                String notificationMessage) {
        AuctionDocument doc = auctionDocRepo.findByCompanyIdAndId(companyId, vehicleDocumentId)
                .orElseThrow(() -> new IllegalStateException("AuctionDocument not found id=" + vehicleDocumentId));
        doc.applyOcrResult(parsed.parsed(), tableHtmlJson);
        auctionDocRepo.save(doc);

        OcrParseJob persistedJob = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new IllegalStateException("OcrParseJob not found id=" + jobId));
        persistedJob.markSucceeded(resultJson);
        jobRepo.save(persistedJob);

        notificationCommandService.createOcr(companyId, requestedByUserId,
                NotificationType.OCR_COMPLETE, DocumentType.AUCTION_CERTIFICATE,
                vehicleId, jobId,
                "경락사실확인서 OCR 완료", notificationMessage);
    }
}
