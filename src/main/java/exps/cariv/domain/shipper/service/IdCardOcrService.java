package exps.cariv.domain.shipper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.shipper.dto.ParsedIdCard;
import exps.cariv.domain.shipper.entity.IdCardDocument;
import exps.cariv.domain.shipper.repository.IdCardDocumentRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 신분증 OCR 처리.
 * - 차량 소유자 신분증: OCR 완료 알림 전송
 * - (레거시) 화주 신분증 문서: 내부 결과 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdCardOcrService {

    private final UpstageService upstageService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final IdCardParserService parser;
    private final DocumentRepository documentRepo;
    private final IdCardDocumentRepository idCardRepo;
    private final NotificationCommandService notificationCommandService;

    @Transactional
    public void processJob(Long companyId, OcrParseJob job) {
        Document doc = documentRepo.findById(job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException("ID_CARD document not found id=" + job.getVehicleDocumentId()));
        if (!Objects.equals(doc.getCompanyId(), companyId)) {
            throw new IllegalStateException("ID_CARD document company mismatch id=" + job.getVehicleDocumentId());
        }
        if (doc.getType() != DocumentType.ID_CARD) {
            throw new IllegalStateException("document type mismatch id=" + job.getVehicleDocumentId()
                    + ", type=" + doc.getType());
        }

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

        // 문서 타입 검증 (경고만)
        DocumentType detected = DocumentTypeDetector.detect(res);
        if (detected != DocumentType.ID_CARD) {
            log.warn("[IdCard OCR] 문서 타입 불일치: detected={}, jobId={}", detected, job.getId());
        }

        ParsedIdCard parsed = parser.parse(res);
        String tableHtmlJson;
        try {
            tableHtmlJson = UpstageTablePayloadBuilder.buildTableHtmlJson(mapper, res);
        } catch (Exception e) {
            throw new IllegalStateException("테이블 파싱 실패: " + e.getMessage(), e);
        }
        String resultJson;
        try {
            resultJson = buildResultJson(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        if (doc instanceof IdCardDocument idCardDocument) {
            idCardDocument.applyOcrResult(
                    parsed.holderName(),
                    parsed.idNumber(),
                    parsed.idAddress(),
                    parsed.issueDate(),
                    tableHtmlJson
            );
            idCardRepo.save(idCardDocument);
        } else {
            // 차량 소유자 신분증은 공통 Document에 OCR 성공 상태만 반영한다.
            doc.markOcrDraft();
            documentRepo.save(doc);
        }

        try {
            job.markSucceeded(resultJson);
        } catch (Exception e) {
            throw new IllegalStateException("Job 상태 저장 실패: " + e.getMessage(), e);
        }
        log.info("[IdCard OCR] 성공 jobId={}, refType={}, refId={}", job.getId(), doc.getRefType(), doc.getRefId());

        if (doc.getRefType() == DocumentRefType.VEHICLE) {
            Long linkedVehicleId = (doc.getRefId() != null && doc.getRefId() > 0) ? doc.getRefId() : null;
            String msg = "소유자 신분증 OCR 완료";
            notificationCommandService.createOcr(
                    companyId,
                    job.getRequestedByUserId(),
                    NotificationType.OCR_COMPLETE,
                    DocumentType.ID_CARD,
                    linkedVehicleId,
                    job.getId(),
                    "소유자 신분증 OCR 완료",
                    msg
            );
        }
    }

    private String buildResultJson(ParsedIdCard parsed) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode parsedNode = mapper.createObjectNode();
        putNullable(parsedNode, "holderName", parsed.holderName());
        putNullable(parsedNode, "idNumber", parsed.idNumber());
        putNullable(parsedNode, "idAddress", parsed.idAddress());
        putNullable(parsedNode, "issueDate", parsed.issueDate());
        root.set("parsed", parsedNode);

        root.set("errorFields", mapper.createArrayNode());
        ArrayNode missing = mapper.createArrayNode();
        if (isBlank(parsed.holderName())) missing.add("holderName");
        if (isBlank(parsed.idNumber())) missing.add("idNumber");
        if (isBlank(parsed.idAddress())) missing.add("idAddress");
        root.set("missingFields", missing);

        return mapper.writeValueAsString(root);
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(key);
            return;
        }
        node.put(key, value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
