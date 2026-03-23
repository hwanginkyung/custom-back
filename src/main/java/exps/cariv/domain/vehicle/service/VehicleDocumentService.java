package exps.cariv.domain.vehicle.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import exps.cariv.domain.auction.dto.request.AuctionSnapshotUpdateRequest;
import exps.cariv.domain.auction.dto.response.AuctionDocumentResponse;
import exps.cariv.domain.document.entity.*;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrQueueService;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.domain.registration.dto.RegistrationSnapshot;
import exps.cariv.domain.registration.dto.request.RegistrationSnapshotUpdateRequest;
import exps.cariv.domain.registration.dto.response.RegistrationDocumentResponse;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.domain.vehicle.dto.OwnerIdCardSnapshot;
import exps.cariv.domain.vehicle.dto.VehiclePatch;
import exps.cariv.domain.vehicle.dto.request.OwnerIdCardSnapshotUpdateRequest;
import exps.cariv.domain.vehicle.entity.OwnerType;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleOwnerDocument;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.dto.response.OwnerIdCardDocumentResponse;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.aws.S3Upload.UploadResult;
import exps.cariv.domain.vehicle.dto.response.DocumentUploadResponse;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

/**
 * 차량 문서 업로드/조회 서비스.
 * - 자동차등록증: S3 업로드 + OCR (필수)
 * - 소유자 신분증: S3 업로드 (ownerType 조건부 필수)
 * - 문서 연결(linkDocumentsToVehicle): 차량 생성 시 문서 바인딩
 *
 * 경락사실확인서/매매계약서는 현재 업로드 플로우에서 비활성화되어 있습니다.
 */
@Service
@RequiredArgsConstructor
public class VehicleDocumentService {

    private final ObjectMapper objectMapper;
    private final DocumentRepository documentRepo;
    private final RegistrationDocumentRepository regDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrQueueService ocrQueueService;
    private final OcrResultNormalizer ocrResultNormalizer;
    private final S3Upload s3Upload;
    private final VehicleRepository vehicleRepo;
    private final PlatformTransactionManager txManager;

    /**
     * 자동차등록증 업로드 → OCR 대기열.
     * S3 업로드와 Redis enqueue는 트랜잭션 밖에서 실행한다.
     */
    public DocumentUploadResponse uploadRegistration(Long companyId, Long userId, MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // 1) 트랜잭션: 문서 row 생성
        Long docId = Objects.requireNonNull(
                tx.execute(status -> {
                    RegistrationDocument doc = RegistrationDocument.createNew(
                            companyId, userId, 0L, "__PENDING__", file.getOriginalFilename());
                    return regDocRepo.saveAndFlush(doc).getId();
                }),
                "failed to create registration document"
        );

        // 2) 트랜잭션 밖: S3 업로드
        UploadResult up = s3Upload.uploadRawDocument(companyId, docId, file);

        // 3) 트랜잭션: 파일 메타 업데이트 + OCR Job 생성
        DocumentUploadResponse response = Objects.requireNonNull(
                tx.execute(status -> {
                    RegistrationDocument doc = regDocRepo.findByCompanyIdAndId(companyId, docId)
                            .orElseThrow(() -> new IllegalStateException("RegistrationDocument not found id=" + docId));
                    doc.replaceFile(userId, up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
                    regDocRepo.save(doc);

                    Long jobId = createOcrJob(companyId, userId, doc.getId(), doc.getS3Key(), DocumentType.REGISTRATION);
                    return new DocumentUploadResponse(doc.getId(), up.s3Key(), jobId);
                }),
                "failed to finalize registration upload"
        );

        // 4) 트랜잭션 밖: Redis enqueue
        ocrQueueService.enqueue(response.jobId());
        return response;
    }

    /**
     * 소유자 신분증 업로드 → OCR 대기열.
     */
    public DocumentUploadResponse uploadOwnerIdCard(Long companyId, Long userId, MultipartFile file) {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Long docId = Objects.requireNonNull(
                tx.execute(status -> {
                    VehicleOwnerDocument doc = VehicleOwnerDocument.createNew(
                            companyId, userId, 0L, "__PENDING__", file.getOriginalFilename(), null, 0L);
                    return documentRepo.saveAndFlush(doc).getId();
                }),
                "failed to create owner id-card document"
        );

        UploadResult up = s3Upload.uploadRawDocument(companyId, docId, file);

        DocumentUploadResponse response = Objects.requireNonNull(
                tx.execute(status -> {
                    Document doc = documentRepo.findById(docId)
                            .orElseThrow(() -> new IllegalStateException("OwnerDocument not found id=" + docId));
                    doc.replaceFile(userId, up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
                    documentRepo.save(doc);
                    Long jobId = createOcrJob(companyId, userId, doc.getId(), doc.getS3Key(), DocumentType.ID_CARD);
                    return new DocumentUploadResponse(doc.getId(), up.s3Key(), jobId);
                }),
                "failed to finalize owner id-card upload"
        );

        ocrQueueService.enqueue(response.jobId());
        return response;
    }

    /**
     * 경락사실확인서 업로드는 비활성화됨.
     */
    public DocumentUploadResponse uploadAuctionCertificate(Long companyId, Long userId, MultipartFile file) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "경락사실확인서 업로드는 현재 비활성화되었습니다.");
    }

    /**
     * OCR Job 생성 (트랜잭션 내에서 호출됨, enqueue는 호출자가 트랜잭션 밖에서 수행).
     */
    private Long createOcrJob(Long companyId, Long userId, Long documentId, String s3Key, DocumentType docType) {
        OcrParseJob job = OcrParseJob.builder()
                .documentType(docType)
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

    /**
     * 차량 생성 시 문서들의 refId를 vehicleId로 연결.
     */
    @Transactional
    public void linkDocumentsToVehicle(Long companyId, Long vehicleId,
                                       Long registrationDocId,
                                       Long ownerIdCardDocId,
                                       OwnerType ownerType) {
        linkDocument(companyId, vehicleId, registrationDocId, DocumentType.REGISTRATION, true);
        linkDocument(companyId, vehicleId, ownerIdCardDocId, DocumentType.ID_CARD, ownerType.isOwnerIdCardRequired());
    }

    private void linkDocument(Long companyId,
                              Long vehicleId,
                              Long docId,
                              DocumentType expectedType,
                              boolean required) {
        if (docId == null) {
            if (required) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "자동차등록증 업로드는 필수입니다.");
            }
            return;
        }

        Document doc = documentRepo.findById(docId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "문서를 찾을 수 없습니다. documentId=" + docId));

        if (!doc.getCompanyId().equals(companyId)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }

        if (doc.getRefType() != DocumentRefType.VEHICLE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "차량 문서만 연결할 수 있습니다. documentId=" + docId);
        }

        if (doc.getType() != expectedType) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT,
                    "문서 타입이 올바르지 않습니다. documentId=" + docId
                            + ", expected=" + expectedType.name()
                            + ", actual=" + doc.getType().name()
            );
        }

        // 이미 다른 차량에 연결된 문서는 재사용 불가
        if (doc.getRefId() != null && doc.getRefId() != 0L && !doc.getRefId().equals(vehicleId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 다른 차량에 연결된 문서입니다. documentId=" + docId);
        }

        doc.linkToVehicle(vehicleId);

        // OcrParseJob의 vehicleId도 업데이트 (companyId + 문서타입 범위로 제한)
        jobRepo.findByCompanyIdAndVehicleDocumentIdAndDocumentType(companyId, docId, expectedType)
                .forEach(job -> job.updateVehicleId(vehicleId));
    }

    /**
     * 차량에 연결된 모든 문서 조회.
     */
    @Transactional(readOnly = true)
    public List<Document> getDocumentsForVehicle(Long companyId, Long vehicleId) {
        return documentRepo.findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                companyId, DocumentRefType.VEHICLE, vehicleId);
    }

    /**
     * documentId 기준 등록증 스냅샷 조회.
     * 차량 생성 전(pre-create)에도 조회 가능하다.
     */
    @Transactional(readOnly = true)
    public RegistrationDocumentResponse getRegistrationSnapshot(Long companyId, Long documentId) {
        RegistrationDocument doc = regDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        var ocrResult = jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, doc.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(OcrFieldResult::empty);

        return new RegistrationDocumentResponse(
                doc.getId(),
                doc.getS3Key(),
                doc.getOriginalFilename(),
                doc.getUploadedAt(),
                doc.getParsedAt(),
                doc.toSnapshot(),
                ocrResult
        );
    }

    /**
     * jobId 기준 등록증 스냅샷 조회.
     */
    @Transactional(readOnly = true)
    public RegistrationDocumentResponse getRegistrationSnapshotByJobId(Long companyId, Long jobId) {
        OcrParseJob job = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (job.getDocumentType() != DocumentType.REGISTRATION) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "등록증 OCR 작업(jobId)이 아닙니다.");
        }
        return getRegistrationSnapshot(companyId, job.getVehicleDocumentId());
    }

    /**
     * documentId 기준 소유자 신분증 OCR 스냅샷 조회.
     * 차량 생성 전(pre-create)에도 조회 가능하다.
     */
    @Transactional(readOnly = true)
    public OwnerIdCardDocumentResponse getOwnerIdCardSnapshot(Long companyId, Long documentId) {
        Document doc = getVehicleOwnerIdCardDocument(companyId, documentId);

        OcrParseJob latestSuccessJob = jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, doc.getId(), OcrJobStatus.SUCCEEDED
                )
                .orElse(null);

        OcrFieldResult ocrResult = latestSuccessJob == null
                ? OcrFieldResult.empty()
                : ocrResultNormalizer.normalize(DocumentType.ID_CARD, latestSuccessJob.getResultJson());

        return new OwnerIdCardDocumentResponse(
                doc.getId(),
                doc.getS3Key(),
                doc.getOriginalFilename(),
                doc.getUploadedAt(),
                latestSuccessJob == null ? null : latestSuccessJob.getFinishedAt(),
                parseOwnerIdCardSnapshot(latestSuccessJob == null ? null : latestSuccessJob.getResultJson()),
                ocrResult
        );
    }

    /**
     * jobId 기준 소유자 신분증 OCR 스냅샷 조회.
     */
    @Transactional(readOnly = true)
    public OwnerIdCardDocumentResponse getOwnerIdCardSnapshotByJobId(Long companyId, Long jobId) {
        OcrParseJob job = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (job.getDocumentType() != DocumentType.ID_CARD) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "신분증 OCR 작업(jobId)이 아닙니다.");
        }
        return getOwnerIdCardSnapshot(companyId, job.getVehicleDocumentId());
    }

    /**
     * 소유자 신분증 OCR 스냅샷 수동 수정 저장.
     */
    @Transactional
    public void updateOwnerIdCardSnapshot(Long companyId,
                                          Long documentId,
                                          OwnerIdCardSnapshotUpdateRequest req) {
        Document doc = getVehicleOwnerIdCardDocument(companyId, documentId);

        OcrParseJob latestJob = jobRepo.findTopByCompanyIdAndVehicleDocumentIdOrderByCreatedAtDesc(companyId, doc.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신분증 OCR 작업을 찾을 수 없습니다."));

        latestJob.markSucceeded(mergeOwnerIdCardResultJson(latestJob.getResultJson(), req));
        jobRepo.save(latestJob);
        doc.markOcrDraft();
        documentRepo.save(doc);
    }

    /**
     * documentId 기준 경락사실확인서 스냅샷 조회.
     * 차량 생성 전(pre-create)에도 조회 가능하다.
     */
    @Transactional(readOnly = true)
    public AuctionDocumentResponse getAuctionSnapshot(Long companyId, Long documentId) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "경락사실확인서 기능은 현재 비활성화되었습니다.");
    }

    /**
     * jobId 기준 경락사실확인서 스냅샷 조회.
     */
    @Transactional(readOnly = true)
    public AuctionDocumentResponse getAuctionSnapshotByJobId(Long companyId, Long jobId) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "경락사실확인서 기능은 현재 비활성화되었습니다.");
    }

    /**
     * 사전 업로드된 등록증 OCR 스냅샷 수동 수정 저장.
     * vehicleId 연결 전에도 documentId만으로 저장 가능하다.
     */
    @Transactional
    public void updateRegistrationSnapshot(Long companyId,
                                           Long documentId,
                                           RegistrationSnapshotUpdateRequest req) {
        RegistrationDocument doc = regDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        RegistrationSnapshot snapshot = new RegistrationSnapshot(
                req.vin(),
                req.vehicleNo(),
                req.carType(),
                req.vehicleUse(),
                req.modelName(),
                req.engineType(),
                req.ownerName(),
                req.ownerId(),
                req.modelYear(),
                req.fuelType(),
                req.manufactureYearMonth(),
                req.displacement(),
                req.firstRegistratedAt(),
                req.mileageKm(),
                req.address(),
                req.modelCode(),
                req.lengthVal(),
                req.widthVal(),
                req.heightVal(),
                req.weight(),
                req.seating(),
                req.maxLoad(),
                req.power()
        );
        doc.applyManualSnapshot(snapshot);
        regDocRepo.save(doc);
        syncVehicleFromRegistration(companyId, doc);
    }

    /**
     * 사전 업로드된 경락사실확인서 OCR 스냅샷 수동 수정 저장.
     * vehicleId 연결 전에도 documentId만으로 저장 가능하다.
     */
    @Transactional
    public void updateAuctionSnapshot(Long companyId,
                                      Long documentId,
                                      AuctionSnapshotUpdateRequest req) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "경락사실확인서 기능은 현재 비활성화되었습니다.");
    }

    private Document getVehicleOwnerIdCardDocument(Long companyId, Long documentId) {
        Document doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (!Objects.equals(doc.getCompanyId(), companyId)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }
        if (doc.getType() != DocumentType.ID_CARD || doc.getRefType() != DocumentRefType.VEHICLE) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "차량 소유자 신분증 문서가 아닙니다.");
        }
        return doc;
    }

    private OwnerIdCardSnapshot parseOwnerIdCardSnapshot(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return new OwnerIdCardSnapshot(null, null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            JsonNode parsedNode = root.path("parsed");
            if (parsedNode.isMissingNode() || !parsedNode.isObject()) {
                parsedNode = root;
            }
            return new OwnerIdCardSnapshot(
                    trimToNull(parsedNode.path("holderName").asText(null)),
                    trimToNull(parsedNode.path("idNumber").asText(null)),
                    trimToNull(parsedNode.path("idAddress").asText(null))
            );
        } catch (Exception e) {
            return new OwnerIdCardSnapshot(null, null, null);
        }
    }

    private String mergeOwnerIdCardResultJson(String existingJson, OwnerIdCardSnapshotUpdateRequest req) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            if (existingJson != null && !existingJson.isBlank()) {
                JsonNode existing = objectMapper.readTree(existingJson);
                if (existing.isObject()) {
                    root.setAll((ObjectNode) existing);
                }
            }

            ObjectNode parsed = root.has("parsed") && root.get("parsed").isObject()
                    ? (ObjectNode) root.get("parsed")
                    : objectMapper.createObjectNode();

            putNullable(parsed, "holderName", req.holderName());
            putNullable(parsed, "idNumber", req.idNumber());
            putNullable(parsed, "idAddress", req.idAddress());
            root.set("parsed", parsed);

            root.set("errorFields", objectMapper.createArrayNode());
            ArrayNode missing = objectMapper.createArrayNode();
            if (isBlank(req.holderName())) missing.add("holderName");
            if (isBlank(req.idNumber())) missing.add("idNumber");
            if (isBlank(req.idAddress())) missing.add("idAddress");
            root.set("missingFields", missing);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "신분증 OCR 스냅샷 저장 중 오류가 발생했습니다.");
        }
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (isBlank(value)) {
            node.putNull(key);
            return;
        }
        node.put(key, value.trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void syncVehicleFromRegistration(Long companyId, RegistrationDocument doc) {
        Long vehicleId = doc.getRefId();
        if (vehicleId == null || vehicleId <= 0) {
            return;
        }

        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        VehiclePatch patch = new VehiclePatch(
                doc.getVin(),
                doc.getVehicleNo(),
                doc.getCarType(),
                doc.getVehicleUse(),
                doc.getModelName(),
                doc.getEngineType(),
                doc.getMileageKm(),
                doc.getOwnerName(),
                doc.getOwnerId(),
                doc.getModelYear(),
                null,
                doc.getManufactureYearMonth(),
                doc.getDisplacement(),
                doc.getFirstRegistratedAt(),
                null,
                doc.getFuelType(),
                null,
                parseInteger(doc.getWeight()),
                parseInteger(doc.getSeating()),
                parseInteger(doc.getLengthVal()),
                parseInteger(doc.getHeightVal()),
                parseInteger(doc.getWidthVal())
        );
        vehicle.applyPatch(patch);
        vehicleRepo.save(vehicle);
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().replaceAll("[^0-9]", "");
        if (normalized.isEmpty()) return null;
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
