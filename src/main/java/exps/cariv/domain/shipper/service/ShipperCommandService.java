package exps.cariv.domain.shipper.service;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrQueueService;
import exps.cariv.domain.shipper.dto.response.ShipperDocumentUploadResponse;
import exps.cariv.domain.shipper.entity.BizRegDocument;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.entity.ShipperDocument;
import exps.cariv.domain.shipper.entity.ShipperType;
import exps.cariv.domain.shipper.repository.BizRegDocumentRepository;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.aws.S3Upload.UploadResult;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShipperCommandService {

    private final ShipperRepository shipperRepo;
    private final DocumentRepository documentRepo;
    private final BizRegDocumentRepository bizRegDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrQueueService ocrQueueService;
    private final S3Upload s3Upload;

    private static final Pattern PHONE_PATTERN = Pattern.compile("^01[016789]-?\\d{3,4}-?\\d{4}$");

    /**
     * 화주 생성.
     * Figma '화주 추가' 모달: 이름, 휴대번호, 화주유형.
     */
    @Transactional
    public Long createShipper(Long companyId, String name, String shipperType, String phone) {
        validatePhone(phone);
        ShipperType parsedType = parseShipperType(shipperType);

        Shipper shipper = Shipper.builder()
                .name(name.trim())
                .shipperType(parsedType)
                .phone(phone != null ? phone.trim() : null)
                .build();
        shipper.setCompanyId(companyId);
        shipper = shipperRepo.save(shipper);

        return shipper.getId();
    }

    /**
     * 화주 문서 업로드.
     * - BIZ_REGISTRATION만 OCR 파이프라인 처리
     * - ID_CARD/ SIGN은 단순 업로드 (확정)
     */
    @Transactional
    public ShipperDocumentUploadResponse uploadDocument(Long companyId, Long userId,
                                                         Long shipperId, String typeStr,
                                                         MultipartFile file) {
        Shipper shipper = getShipperOrThrow(companyId, shipperId);
        DocumentType docType = resolveDocType(typeStr);
        validateDocTypeForShipperType(shipper, docType);

        // S3 업로드
        UploadResult up = s3Upload.uploadVehicleDocument(companyId, "shipper-" + docType.name().toLowerCase(), file);

        // 같은 타입 기존 문서를 하드 삭제 후 교체한다.
        hardDeleteExistingDocuments(companyId, shipperId, docType);

        return switch (docType) {
            case BIZ_REGISTRATION -> uploadBizRegWithOcr(companyId, userId, shipperId, up);
            case ID_CARD -> uploadSimple(companyId, userId, shipperId, DocumentType.ID_CARD, up);
            default -> uploadSimple(companyId, userId, shipperId, docType, up);
        };
    }

    /**
     * 화주 문서 삭제.
     * type 슬롯 기준으로 해당 타입 문서를 모두 제거한다.
     */
    @Transactional
    public void deleteDocument(Long companyId, Long shipperId, String typeStr) {
        getShipperOrThrow(companyId, shipperId);
        DocumentType docType = resolveDocType(typeStr);
        hardDeleteExistingDocuments(companyId, shipperId, docType);
    }

    @Transactional
    public void deleteShipper(Long companyId, Long shipperId) {
        Shipper shipper = getShipperOrThrow(companyId, shipperId);
        shipper.deactivate();
    }

    /**
     * 사업자등록증 업로드 → OCR 대기열.
     */
    private ShipperDocumentUploadResponse uploadBizRegWithOcr(Long companyId, Long userId,
                                                               Long shipperId, UploadResult up) {
        BizRegDocument doc = BizRegDocument.createNew(
                companyId, userId, shipperId, up.s3Key(),
                up.originalFilename(), up.contentType(), up.sizeBytes());
        doc = bizRegDocRepo.saveAndFlush(doc);

        OcrParseJob job = createOcrJob(companyId, userId, shipperId, doc.getId(),
                DocumentType.BIZ_REGISTRATION, doc.getS3Key());

        return new ShipperDocumentUploadResponse(doc.getId(), up.s3Key(), toApiType(DocumentType.BIZ_REGISTRATION));
    }

    /**
     * 사인방 → 단순 업로드 (OCR 없음, 바로 CONFIRMED).
     */
    private ShipperDocumentUploadResponse uploadSimple(Long companyId, Long userId,
                                                        Long shipperId, DocumentType docType,
                                                        UploadResult up) {
        ShipperDocument doc = ShipperDocument.createNew(
                companyId, userId, shipperId, docType,
                up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
        doc = documentRepo.save(doc);
        return new ShipperDocumentUploadResponse(doc.getId(), up.s3Key(), toApiType(docType));
    }

    private void hardDeleteExistingDocuments(Long companyId, Long shipperId, DocumentType docType) {
        java.util.List<Document> existingDocs = documentRepo
                .findAllByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                        companyId, DocumentRefType.SHIPPER, shipperId, docType
                );
        if (existingDocs.isEmpty()) {
            return;
        }

        for (Document existing : existingDocs) {
            jobRepo.findByCompanyIdAndVehicleDocumentIdAndDocumentType(companyId, existing.getId(), docType)
                    .forEach(jobRepo::delete);
            // 문서 레코드 삭제와 함께 S3 원본도 물리 삭제한다. (삭제 실패는 로그 후 계속 진행)
            try {
                s3Upload.deleteByKey(existing.getS3Key());
            } catch (Exception e) {
                // DB 레코드는 삭제되어야 하므로 S3 실패는 경고만 남긴다.
                log.warn("화주 문서 S3 삭제 실패 shipperId={}, documentId={}, key={}",
                        shipperId, existing.getId(), existing.getS3Key(), e);
            }
        }
        documentRepo.deleteAll(existingDocs);
    }

    /**
     * OCR Job 생성 + Redis enqueue.
     * 화주 문서는 vehicleId 대신 shipperId를 vehicleId 필드에 저장.
     */
    private OcrParseJob createOcrJob(Long companyId, Long userId, Long shipperId,
                                      Long documentId, DocumentType docType, String s3Key) {
        OcrParseJob job = OcrParseJob.builder()
                .documentType(docType)
                .status(OcrJobStatus.QUEUED)
                .vehicleId(shipperId)           // 화주 문서: shipperId 저장
                .vehicleDocumentId(documentId)
                .requestedByUserId(userId)
                .s3KeySnapshot(s3Key)
                .build();
        job.setCompanyId(companyId);
        job = jobRepo.save(job);

        enqueueAfterCommit(job.getId());
        return job;
    }

    // ─── 수정 API ───

    /**
     * 휴대전화 수정.
     */
    @Transactional
    public void updatePhone(Long companyId, Long shipperId, String phone) {
        validatePhone(phone);
        Shipper shipper = getShipperOrThrow(companyId, shipperId);
        shipper.updatePhone(phone);
    }

    private void validatePhone(String phone) {
        if (phone != null && !phone.isBlank()
                && !PHONE_PATTERN.matcher(phone.replaceAll("-", "").trim()).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "휴대전화 번호 형식이 올바르지 않습니다. (예: 010-1234-5678)");
        }
    }

    private ShipperType parseShipperType(String shipperType) {
        try {
            return ShipperType.from(shipperType);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    private void validateDocTypeForShipperType(Shipper shipper, DocumentType docType) {
        if (shipper.getShipperType() == ShipperType.CORPORATE_BUSINESS && docType == DocumentType.ID_CARD) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT,
                    "법인사업자 화주는 신분증(ID_CARD)을 업로드할 수 없습니다."
            );
        }
    }

    private Shipper getShipperOrThrow(Long companyId, Long shipperId) {
        return shipperRepo.findByIdAndCompanyIdAndActiveTrue(shipperId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHIPPER_NOT_FOUND));
    }

    /**
     * API 명세의 type 문자열을 DocumentType으로 변환.
     */
    private DocumentType resolveDocType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "문서 타입을 지정해주세요.");
        }
        return switch (typeStr.toUpperCase().trim()) {
            case "CEO_ID", "ID_CARD" -> DocumentType.ID_CARD;
            case "BIZ_REG", "BIZ_REGISTRATION" -> DocumentType.BIZ_REGISTRATION;
            case "SIGN" -> DocumentType.SIGN;
            default -> throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 문서 타입입니다: " + typeStr
                            + " (허용: CEO_ID, ID_CARD, BIZ_REG, BIZ_REGISTRATION, SIGN)");
        };
    }

    /**
     * 프론트 슬롯 키와 맞추기 위한 응답 타입명.
     */
    private String toApiType(DocumentType type) {
        return switch (type) {
            case ID_CARD -> "CEO_ID";
            case BIZ_REGISTRATION -> "BIZ_REG";
            default -> type.name();
        };
    }

    private void enqueueAfterCommit(Long jobId) {
        if (jobId == null || jobId <= 0) return;

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            ocrQueueService.enqueue(jobId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ocrQueueService.enqueue(jobId);
            }
        });
    }
}
