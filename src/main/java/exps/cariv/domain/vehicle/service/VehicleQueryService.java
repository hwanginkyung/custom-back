package exps.cariv.domain.vehicle.service;

import exps.cariv.domain.customs.entity.CustomsRequest;
import exps.cariv.domain.customs.entity.ShippingMethod;
import exps.cariv.domain.customs.repository.CustomsRequestVehicleRepository;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.malso.print.MalsoPrintService;
import exps.cariv.domain.vehicle.dto.request.VehicleListRequest;
import exps.cariv.domain.vehicle.dto.response.VehicleDetailResponse.AttachmentSection;
import exps.cariv.domain.vehicle.dto.response.VehicleDetailResponse.AttachmentSlot;
import exps.cariv.domain.vehicle.dto.response.VehicleDetailResponse;
import exps.cariv.domain.vehicle.dto.response.VehicleDetailResponse.DocumentInfo;
import exps.cariv.domain.vehicle.dto.response.VehicleListResponse;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.repository.VehicleSpecification;
import exps.cariv.global.aws.S3ObjectReader;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.tenant.aspect.TenantFiltered;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@TenantFiltered
public class VehicleQueryService {

    private static final String CUSTOMS_GENERATED_DOCS_PREFIX = "generated-docs/customs/";
    private static final String CUSTOMS_GENERATED_DOCS_VERSION = "v1";
    private static final String CUSTOMS_INVOICE_FILENAME = "invoice.pdf";

    private static final String SLOT_REGISTRATION = "REGISTRATION";
    private static final String SLOT_OWNER_ID_CARD = "OWNER_ID_CARD";
    private static final String SLOT_DEREGISTRATION = "DEREGISTRATION";
    private static final String SLOT_DEREG_APP = "DEREGISTRATION_APP";
    private static final String SLOT_MALSO_INVOICE = "MALSO_INVOICE_PACKING";
    private static final String SLOT_MALSO_OWNER_BIZ_COMBINED = "MALSO_OWNER_BIZ_COMBINED";
    private static final String SLOT_EXPORT_CERT = "EXPORT_CERTIFICATE";
    private static final String SLOT_CUSTOMS_INVOICE = "CUSTOMS_INVOICE_PACKING";
    private static final String SLOT_CUSTOMS_ATTACHMENT = "CUSTOMS_ATTACHMENT";

    private static final String SECTION_REGISTRATION = "VEHICLE_REGISTRATION";
    private static final String SECTION_DEREGISTRATION = "DEREGISTRATION";
    private static final String SECTION_CERTIFICATE = "CERTIFICATE";

    private static final String MALSO_KEY_DEREG_APP = "deregistration_app";
    private static final String MALSO_KEY_INVOICE = "invoice";
    private static final String MALSO_KEY_OWNER_BIZ_COMBINED = "owner_id_biz_reg_combined";

    private final VehicleRepository vehicleRepo;
    private final VehicleDocumentService documentService;
    private final MalsoPrintService malsoPrintService;
    private final CustomsRequestVehicleRepository customsRequestVehicleRepo;
    private final S3ObjectReader s3ObjectReader;
    private final S3Upload s3Upload;

    /**
     * 차량 목록 조회 (필터 + 페이징).
     */
    public Page<VehicleListResponse> list(Long companyId, VehicleListRequest req) {
        List<VehicleStage> stages = parseStages(req.stage());

        Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                .and(VehicleSpecification.notDeleted())
                .and(VehicleSpecification.stageIn(stages))
                .and(VehicleSpecification.keywordLike(req.keyword()))
                .and(VehicleSpecification.shipperNameIs(req.shipperName()))
                .and(VehicleSpecification.createdAfter(req.startDate()))
                .and(VehicleSpecification.createdBefore(req.endDate()))
                .and(VehicleSpecification.purchaseDateAfter(req.purchaseFrom()))
                .and(VehicleSpecification.purchaseDateBefore(req.purchaseTo()));

        PageRequest pageable = PageRequest.of(req.page(), req.size(), Sort.by(Sort.Direction.DESC, "createdAt"));

        return vehicleRepo.findAll(spec, pageable).map(this::toListResponse);
    }

    /**
     * 차량 상세 조회.
     */
    public VehicleDetailResponse detail(Long companyId, Long vehicleId) {
        Vehicle v = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        List<Document> docs = documentService.getDocumentsForVehicle(companyId, vehicleId);
        List<DocumentInfo> docInfos = docs.stream()
                .map(d -> new DocumentInfo(
                        d.getId(),
                        d.getType().name(),
                        d.getStatus().name(),
                        d.getS3Key(),
                        d.getOriginalFilename(),
                        d.getUploadedAt()
                ))
                .toList();
        List<AttachmentSection> attachmentSections = buildAttachmentSections(companyId, vehicleId, docs);

        return new VehicleDetailResponse(
                v.getId(),
                v.getStage().name(),
                v.getCreatedAt(),
                v.getVin(),
                v.getVehicleNo(),
                v.getCarType(),
                v.getVehicleUse(),
                v.getModelName(),
                v.getEngineType(),
                v.getMileageKm(),
                v.getOwnerName(),
                v.getOwnerId(),
                v.getModelYear(),
                v.getOwnerType() == null ? null : v.getOwnerType().name(),
                v.getManufactureYearMonth(),
                v.getDisplacement(),
                v.getFirstRegistrationDate(),
                v.getTransmission() == null ? null : v.getTransmission().name(),
                v.getFuelType(),
                v.getColor(),
                v.getWeight(),
                v.getSeatingCapacity(),
                v.getLength(),
                v.getHeight(),
                v.getWidth(),
                v.getShipperName(),
                v.getShipperId(),
                v.getPurchasePrice(),
                v.getPurchaseDate(),
                v.getSaleAmount(),
                v.getSaleDate(),
                docInfos,
                attachmentSections
        );
    }

    // ─── 내부 ───

    private List<AttachmentSection> buildAttachmentSections(Long companyId, Long vehicleId, List<Document> docs) {
        Map<DocumentType, Document> latestDocs = latestDocumentByType(docs);
        Map<String, MalsoPrintService.PrintItem> malsoItems = loadMalsoItems(companyId, vehicleId);
        boolean exposeMalsoGenerated = shouldExposeMalsoGenerated(malsoItems);
        GeneratedSlot customsInvoice = loadCustomsInvoiceSlot(companyId, vehicleId);
        AttachmentSlot customsAttachment = loadCustomsAttachmentSlot(companyId, vehicleId);

        return List.of(
                new AttachmentSection(
                        SECTION_REGISTRATION,
                        "차량 등록",
                        List.of(
                                toDocumentSlot(vehicleId, SLOT_REGISTRATION, "자동차등록증", latestDocs.get(DocumentType.REGISTRATION))
                        )
                ),
                new AttachmentSection(
                        SECTION_DEREGISTRATION,
                        "말소",
                        List.of(
                                toDocumentSlot(vehicleId, SLOT_DEREGISTRATION, "말소증", latestDocs.get(DocumentType.DEREGISTRATION)),
                                toMalsoGeneratedSlot(vehicleId, SLOT_DEREG_APP, "말소신청서", malsoItems.get(MALSO_KEY_DEREG_APP), exposeMalsoGenerated),
                                toMalsoGeneratedSlot(vehicleId, SLOT_MALSO_INVOICE, "invoice/packinglist(말소용)", malsoItems.get(MALSO_KEY_INVOICE), exposeMalsoGenerated),
                                toMalsoGeneratedSlot(
                                        vehicleId,
                                        SLOT_MALSO_OWNER_BIZ_COMBINED,
                                        "대표자신분증+사업자등록증(화주)",
                                        malsoItems.get(MALSO_KEY_OWNER_BIZ_COMBINED),
                                        exposeMalsoGenerated
                                )
                        )
                ),
                new AttachmentSection(
                        SECTION_CERTIFICATE,
                        "필증",
                        List.of(
                                toDocumentSlot(vehicleId, SLOT_EXPORT_CERT, "신고필증", latestDocs.get(DocumentType.EXPORT_CERTIFICATE)),
                                toGeneratedSlot(vehicleId, SLOT_CUSTOMS_INVOICE, "invoice/packinglist(필증용)", customsInvoice),
                                customsAttachment
                        )
                ),
                new AttachmentSection(
                        "ETC",
                        "기타 (선택사항)",
                        List.of(
                                toDocumentSlot(vehicleId, SLOT_OWNER_ID_CARD, "차주 신분증", latestDocs.get(DocumentType.ID_CARD))
                        )
                )
        );
    }

    private Map<DocumentType, Document> latestDocumentByType(List<Document> docs) {
        Map<DocumentType, Document> latestByType = new EnumMap<>(DocumentType.class);
        for (Document doc : docs) {
            latestByType.putIfAbsent(doc.getType(), doc);
        }
        return latestByType;
    }

    private Map<String, MalsoPrintService.PrintItem> loadMalsoItems(Long companyId, Long vehicleId) {
        try {
            Map<String, MalsoPrintService.PrintItem> itemMap = new java.util.HashMap<>();
            for (MalsoPrintService.PrintItem item : malsoPrintService.getItems(companyId, vehicleId).items()) {
                itemMap.put(item.key(), item);
            }
            return itemMap;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private AttachmentSlot toDocumentSlot(Long vehicleId, String slotKey, String slotName, Document doc) {
        if (doc == null || isBlank(doc.getS3Key())) {
            return emptySlot(slotKey, slotName);
        }
        String previewUrl = attachmentPreviewUrl(vehicleId, slotKey);
        String downloadUrl = attachmentDownloadUrl(vehicleId, slotKey);
        return new AttachmentSlot(
                slotKey,
                slotName,
                true,
                doc.getId(),
                doc.getType().name(),
                doc.getStatus().name(),
                doc.getS3Key(),
                previewUrl,
                downloadUrl,
                doc.getOriginalFilename(),
                doc.getSizeBytes(),
                doc.getUploadedAt(),
                doc.getContentType()
        );
    }

    private AttachmentSlot toMalsoGeneratedSlot(Long vehicleId, String slotKey, String slotName, MalsoPrintService.PrintItem item, boolean expose) {
        if (!expose) {
            return emptySlot(slotKey, slotName);
        }
        if (item == null || !item.available() || isBlank(item.s3Key()) || isBlank(item.s3Url())) {
            return emptySlot(slotKey, slotName);
        }
        return new AttachmentSlot(
                slotKey,
                slotName,
                true,
                item.documentId(),
                item.documentType(),
                "GENERATED",
                item.s3Key(),
                attachmentPreviewUrl(vehicleId, slotKey),
                attachmentDownloadUrl(vehicleId, slotKey),
                item.filename(),
                item.sizeBytes(),
                item.date(),
                item.contentType()
        );
    }

    private boolean shouldExposeMalsoGenerated(Map<String, MalsoPrintService.PrintItem> malsoItems) {
        if (malsoItems == null || malsoItems.isEmpty()) return false;
        MalsoPrintService.PrintItem invoice = malsoItems.get(MALSO_KEY_INVOICE);
        return invoice != null && invoice.available();
    }

    private AttachmentSlot toGeneratedSlot(Long vehicleId, String slotKey, String slotName, GeneratedSlot generated) {
        if (generated == null || !generated.available()) {
            return emptySlot(slotKey, slotName);
        }
        return new AttachmentSlot(
                slotKey,
                slotName,
                true,
                null,
                generated.documentType(),
                "GENERATED",
                generated.s3Key(),
                attachmentPreviewUrl(vehicleId, slotKey),
                attachmentDownloadUrl(vehicleId, slotKey),
                generated.filename(),
                generated.sizeBytes(),
                generated.uploadedAt(),
                generated.contentType()
        );
    }

    private AttachmentSlot loadCustomsAttachmentSlot(Long companyId, Long vehicleId) {
        return customsRequestVehicleRepo.findFirstByVehicleIdOrderByCreatedAtDesc(vehicleId)
                .map(crv -> {
                    CustomsRequest request = crv.getCustomsRequest();
                    ShippingMethod method = request == null ? null : request.getShippingMethod();
                    List<String> keys = new ArrayList<>();
                    String slotName = "첨부사진";

                    if (method == ShippingMethod.RORO) {
                        if (request != null) {
                            addIfPresent(keys, request.getContainerPhoto1S3Key());
                            addIfPresent(keys, request.getContainerPhoto2S3Key());
                            addIfPresent(keys, request.getContainerPhoto3S3Key());
                        }
                    } else {
                        addIfPresent(keys, crv.getVehiclePhoto1S3Key());
                        addIfPresent(keys, crv.getVehiclePhoto2S3Key());
                        addIfPresent(keys, crv.getVehiclePhoto3S3Key());
                        addIfPresent(keys, crv.getVehiclePhoto4S3Key());
                        if (request != null) {
                            addIfPresent(keys, request.getContainerPhoto1S3Key());
                            addIfPresent(keys, request.getContainerPhoto2S3Key());
                            addIfPresent(keys, request.getContainerPhoto3S3Key());
                        }
                    }

                    if (keys.isEmpty()) {
                        return emptySlot(SLOT_CUSTOMS_ATTACHMENT, slotName);
                    }

                    String s3Key = keys.get(0);
                    S3ObjectReader.S3ObjectMeta meta = s3ObjectReader.readMeta(s3Key);
                    if (meta == null) {
                        return emptySlot(SLOT_CUSTOMS_ATTACHMENT, slotName);
                    }

                    return new AttachmentSlot(
                            SLOT_CUSTOMS_ATTACHMENT,
                            slotName,
                            true,
                            null,
                            method == ShippingMethod.RORO ? "SHORING_LIST" : "PHOTO",
                            "UPLOADED",
                            s3Key,
                            attachmentPreviewUrl(vehicleId, SLOT_CUSTOMS_ATTACHMENT),
                            attachmentDownloadUrl(vehicleId, SLOT_CUSTOMS_ATTACHMENT),
                            filenameFromKey(s3Key),
                            meta.sizeBytes(),
                            meta.lastModified(),
                                nvl(meta.contentType(), "application/octet-stream")
                    );
                })
                .orElseGet(() -> {
                    return emptySlot(SLOT_CUSTOMS_ATTACHMENT, "첨부사진");
                });
    }

    private GeneratedSlot loadCustomsInvoiceSlot(Long companyId, Long vehicleId) {
        return customsRequestVehicleRepo.findFirstByVehicleIdOrderByCreatedAtDesc(vehicleId)
                .map(crv -> {
                    CustomsRequest request = crv.getCustomsRequest();
                    if (request == null || request.getId() == null) {
                        return GeneratedSlot.empty("INVOICE");
                    }
                    String s3Key = customsGeneratedDocKey(companyId, request.getId(), CUSTOMS_INVOICE_FILENAME);
                    S3ObjectReader.S3ObjectMeta meta = s3ObjectReader.readMeta(s3Key);
                    if (meta == null) {
                        return GeneratedSlot.empty("INVOICE");
                    }
                    String s3Url = s3Upload.toUrl(s3Key);
                    return new GeneratedSlot(
                            true,
                            "INVOICE",
                            s3Key,
                            s3Url,
                            s3Url,
                            CUSTOMS_INVOICE_FILENAME,
                            meta.sizeBytes(),
                            meta.lastModified(),
                            nvl(meta.contentType(), "application/pdf")
                    );
                })
                .orElseGet(() -> GeneratedSlot.empty("INVOICE"));
    }

    public AttachmentFileData loadAttachmentFile(Long companyId, Long vehicleId, String slotKey) {
        if (slotKey == null || slotKey.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }
        List<Document> docs = documentService.getDocumentsForVehicle(companyId, vehicleId);
        AttachmentSlot slot = buildAttachmentSections(companyId, vehicleId, docs).stream()
                .flatMap(section -> section.slots().stream())
                .filter(s -> slotKey.equalsIgnoreCase(s.slotKey()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (!slot.available() || isBlank(slot.s3Key())) {
            throw new CustomException(ErrorCode.NOT_FOUND, "첨부파일이 등록되지 않았습니다.");
        }

        S3ObjectReader.S3ObjectData data = s3ObjectReader.readObject(slot.s3Key());
        if (data == null || data.bytes() == null || data.bytes().length == 0) {
            throw new CustomException(ErrorCode.NOT_FOUND, "첨부파일을 찾을 수 없습니다.");
        }

        String filename = firstNonBlank(
                slot.originalFilename(),
                filenameFromKey(slot.s3Key()),
                slot.slotName() + ".bin"
        );
        String contentType = firstNonBlank(
                slot.contentType(),
                data.contentType(),
                "application/octet-stream"
        );
        return new AttachmentFileData(filename, contentType, data.bytes());
    }

    private String customsGeneratedDocKey(Long companyId, Long requestId, String filename) {
        String safeFilename = filename == null || filename.isBlank()
                ? "document.pdf"
                : filename.replaceAll("[\\\\/<>:\"|?*]", "_");
        return CUSTOMS_GENERATED_DOCS_PREFIX
                + companyId + "/"
                + requestId + "/"
                + CUSTOMS_GENERATED_DOCS_VERSION + "/"
                + safeFilename;
    }

    private void addIfPresent(List<String> keys, String key) {
        if (!isBlank(key)) {
            keys.add(key);
        }
    }

    private String filenameFromKey(String key) {
        if (isBlank(key)) return null;
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    private String attachmentPreviewUrl(Long vehicleId, String slotKey) {
        return "/api/vehicle/detail/" + vehicleId + "/attachments/" + slotKey + "/preview";
    }

    private String attachmentDownloadUrl(Long vehicleId, String slotKey) {
        return "/api/vehicle/detail/" + vehicleId + "/attachments/" + slotKey + "/download";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nvl(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private AttachmentSlot emptySlot(String slotKey, String slotName) {
        return new AttachmentSlot(
                slotKey,
                slotName,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private record GeneratedSlot(
            boolean available,
            String documentType,
            String s3Key,
            String previewUrl,
            String downloadUrl,
            String filename,
            Long sizeBytes,
            Instant uploadedAt,
            String contentType
    ) {
        private static GeneratedSlot empty(String documentType) {
            return new GeneratedSlot(false, documentType, null, null, null, null, null, null, null);
        }
    }

    private VehicleListResponse toListResponse(Vehicle v) {
        return new VehicleListResponse(
                v.getId(),
                v.getStage().name(),
                v.getCreatedAt(),
                v.getVehicleNo(),
                v.getModelName(),
                v.getVin(),
                v.getModelYear(),
                v.getCarType(),
                v.getShipperName(),
                v.getOwnerType() == null ? null : v.getOwnerType().name(),
                v.getOwnerName(),
                v.getPurchaseDate(),
                v.getPurchasePrice(),
                v.isRefundApplied(),
                null,  // purchaseCompanyName - 추후 구현
                v.getLicenseDate()
        );
    }

    private List<VehicleStage> parseStages(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .map(v -> {
                    try {
                        return VehicleStage.valueOf(v.toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public record AttachmentFileData(
            String filename,
            String contentType,
            byte[] bytes
    ) {}
}
