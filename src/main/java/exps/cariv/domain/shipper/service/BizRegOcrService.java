package exps.cariv.domain.shipper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.shipper.dto.ParsedBizReg;
import exps.cariv.domain.shipper.entity.BizRegDocument;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.repository.BizRegDocumentRepository;
import exps.cariv.domain.shipper.repository.ShipperRepository;
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

/**
 * 사업자등록증 OCR 처리 (내부 저장용, 알림 없음).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BizRegOcrService {

    private final UpstageService upstageService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final BizRegParserService parser;
    private final BizRegDocumentRepository bizRegRepo;
    private final ShipperRepository shipperRepo;

    @Transactional
    public void processJob(Long companyId, OcrParseJob job) {
        BizRegDocument doc = bizRegRepo.findByCompanyIdAndId(companyId, job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException("BizRegDocument not found id=" + job.getVehicleDocumentId()));

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

        // 문서 타입 검증 (옵션: 사업자등록증이 아니어도 내부용이니 경고만)
        DocumentType detected = DocumentTypeDetector.detect(res);
        if (detected != DocumentType.BIZ_REGISTRATION) {
            log.warn("[BizReg OCR] 문서 타입 불일치: detected={}, jobId={}", detected, job.getId());
        }

        ParsedBizReg parsed = parser.parse(res);
        String tableHtmlJson;
        try {
            tableHtmlJson = UpstageTablePayloadBuilder.buildTableHtmlJson(mapper, res);
        } catch (Exception e) {
            throw new IllegalStateException("테이블 파싱 실패: " + e.getMessage(), e);
        }

        doc.applyOcrResult(
                parsed.companyName(), parsed.representativeName(),
                parsed.bizNumber(), parsed.bizType(), parsed.bizCategory(),
                parsed.bizAddress(), parsed.openDate(), tableHtmlJson
        );
        bizRegRepo.save(doc);
        syncShipperMasterFromBizReg(companyId, doc.getRefId(), parsed.bizNumber(), parsed.bizAddress());

        try {
            job.markSucceeded(mapper.writeValueAsString(parsed));
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }
        log.info("[BizReg OCR] 성공 jobId={}, shipperId={}", job.getId(), job.getVehicleId());
    }

    private void syncShipperMasterFromBizReg(Long companyId, Long shipperId,
                                             String bizNumber, String bizAddress) {
        if (shipperId == null) {
            return;
        }

        String normalizedBizNumber = normalizeBizNumber(bizNumber);
        String normalizedAddress = normalizeNonBlank(bizAddress);
        if (normalizedBizNumber == null && normalizedAddress == null) {
            return;
        }

        Shipper shipper = shipperRepo.findByIdAndCompanyId(shipperId, companyId).orElse(null);
        if (shipper == null) {
            log.warn("[BizReg OCR] 화주 마스터 없음 shipperId={}", shipperId);
            return;
        }

        shipper.update(
                shipper.getName(),
                shipper.getType(),
                shipper.getShipperType(),
                shipper.getPhone(),
                normalizedBizNumber,
                normalizedAddress
        );
    }

    private String normalizeBizNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != 10) {
            return null;
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }

    private String normalizeNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
