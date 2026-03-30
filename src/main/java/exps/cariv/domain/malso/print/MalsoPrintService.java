package exps.cariv.domain.malso.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.customs.entity.CustomsRequestVehicle;
import exps.cariv.domain.customs.entity.ContainerInfo;
import exps.cariv.domain.customs.entity.InvoiceNumberType;
import exps.cariv.domain.customs.entity.ShippingMethod;
import exps.cariv.domain.customs.entity.TradeCondition;
import exps.cariv.domain.customs.repository.CustomsRequestVehicleRepository;
import exps.cariv.domain.customs.service.CustomsInvoiceXlsxGenerator;
import exps.cariv.domain.customs.service.InvoiceNumberService;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.domain.shipper.dto.ParsedIdCard;
import exps.cariv.domain.shipper.entity.BizRegDocument;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.domain.shipper.service.IdCardLlmRefiner;
import exps.cariv.domain.shipper.service.IdCardParserService;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import exps.cariv.domain.upstage.service.UpstageService;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.aws.S3ObjectReader;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 말소 출력 서비스.
 *
 * <p>출력 모달에서 필요한 문서:
 * <ol>
 *   <li>말소증 (완료 차량에서만 노출, 최상단)</li>
 *   <li>소유자 신분증 (차량에 업로드된 ID_CARD 문서)</li>
 *   <li>대표자신분증+사업자등록증(화주) (사업자등록증 위에 소유자 신분증 합성)</li>
 *   <li>말소등록신청서 (XLSX 템플릿 → PDF 자동생성)</li>
 *   <li>Invoice / Packing List (XLSX 템플릿 → PDF 자동생성)</li>
 * </ol>
 *
 * <p>생성 전략: 최초 요청 시 생성 후 S3에 캐시, 이후 요청 시 캐시 반환.
 * <p>파일명에 차대번호(VIN)를 포함하여 구분 가능하게 합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MalsoPrintService {

    private final VehicleRepository vehicleRepo;
    private final ShipperRepository shipperRepo;
    private final RegistrationDocumentRepository registrationDocRepo;
    private final DocumentRepository documentRepo;
    private final CustomsRequestVehicleRepository customsRequestVehicleRepo;
    private final S3ObjectReader s3Reader;
    private final S3Upload s3Upload;
    private final UpstageService upstageService;
    private final ObjectMapper objectMapper;
    private final IdCardParserService idCardParserService;
    private final IdCardLlmRefiner idCardLlmRefiner;
    private final OcrParseJobRepository jobRepo;
    private final MalsoXlsxGenerator malsoXlsxGenerator;
    private final CustomsInvoiceXlsxGenerator customsInvoiceXlsxGenerator;
    private final InvoiceNumberService invoiceNumberService;
    private final XlsxToPdfConverter pdfConverter;

    // S3 캐시 경로 prefix
    private static final String CACHE_PREFIX = "print-cache/malso/";
    private static final String CACHE_VERSION = "v18";
    private static final String DEREG_APP_TEMPLATE_VERSION = "a4_v3_20260323_printarea_fix";
    private static final Pattern KOR_ID_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})[- ]?(\\d{7})(?!\\d)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
    private static final BigDecimal MM3_PER_M3 = new BigDecimal("1000000000");
    private static final long VIRTUAL_DEREGISTRATION_APP_DOC_ID = -1001L;
    private static final long VIRTUAL_INVOICE_DOC_ID = -1002L;
    private static final long VIRTUAL_OWNER_BIZ_COMBINED_DOC_ID = -1003L;
    private static final String DEFAULT_MALSO_SHIPPER_NAME = "CARIV EXPORT";
    private static final String DEFAULT_MALSO_SHIPPER_ADDRESS = "SEOUL, REPUBLIC OF KOREA";
    private static final String DEFAULT_MALSO_SHIPPER_PHONE = "+82-2-0000-0000";
    private static final String DEFAULT_MALSO_CONSIGNEE_NAME = "MOHAMMED AL RASHID";
    private static final String DEFAULT_MALSO_CONSIGNEE_ADDRESS = "DUBAI, UNITED ARAB EMIRATES";
    private static final String DEFAULT_MALSO_CONSIGNEE_PHONE = "";
    private static final String DEFAULT_MALSO_EXPORT_PORT = "INCHEON PORT";
    private static final String DEFAULT_MALSO_DESTINATION_COUNTRY = "UNITED ARAB EMIRATES";
    private static final String DEFAULT_MALSO_WAREHOUSE_LOCATION = "INCHEON PORT";
    private static final String DEFAULT_MALSO_RORO_REMARK = "";
    private static final String DEFAULT_MALSO_MODEL_NAME = "KOREAN USED CAR";
    private static final String DEFAULT_MALSO_VIN = "KNAXXXXXXXXXXXXXXX";
    private static final String DEFAULT_MALSO_FUEL = "Gasoline";
    private static final int DEFAULT_MALSO_ENGINE_CC = 1998;
    private static final long DEFAULT_MALSO_PRICE = 5000L;
    private static final long INVOICE_RANDOM_MIN = 4_000L;
    private static final long INVOICE_RANDOM_MAX = 9_999L;
    private static final BigDecimal DEFAULT_MALSO_WEIGHT_KG = new BigDecimal("1500");
    private static final BigDecimal DEFAULT_MALSO_CBM = new BigDecimal("10.000");
    private static final String MALSO_INVOICE_TITLE = "Commercial Invoice · Packing List";
    private static final String MALSO_INVOICE_TITLE_CELL = "A1";
    private static final String MALSO_CONSIGNEE_ADDRESS_CELL = "K6";
    private static final String MALSO_CONSIGNEE_PHONE_CELL = "K9";
    private static final String MALSO_RORO_REMARK_CELL = "M13";
    private static final String FILE_DEREG_CERT_PREFIX = "말소증_";
    private static final String FILE_DEREG_APP_PREFIX = "말소신청서_";
    private static final String FILE_OWNER_ID_PREFIX = "소유자_신분증_";
    private static final String FILE_OWNER_BIZ_PREFIX = "사업자등록증_신분증_";
    // 문서 key는 프론트 호환을 위해 유지합니다.
    private static final String KEY_DEREGISTRATION = "deregistration";
    private static final String KEY_ID_CARD = "id_card";
    private static final String KEY_DEREGISTRATION_APP = "deregistration_app";
    private static final String KEY_INVOICE = "invoice";
    private static final String KEY_OWNER_BIZ_COMBINED = "owner_id_biz_reg_combined";
    private static final String INVOICE_CACHE_DOC_TYPE = "invoice_no_sign";
    private static final Set<String> VALID_KEYS = Set.of(
            KEY_DEREGISTRATION,
            KEY_ID_CARD,
            KEY_DEREGISTRATION_APP,
            KEY_INVOICE,
            KEY_OWNER_BIZ_COMBINED
    );

    /**
     * 출력 모달에 보여줄 문서 아이템 목록 조회 (read-only).
     * 자동 생성 문서는 캐시된 PDF 메타데이터를 기준으로 반환합니다.
     */
    @Transactional(readOnly = true)
    public PrintItemsResponse getItems(Long companyId, Long vehicleId) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();
        Document deregistrationDoc = ctx.deregistrationDoc().orElse(null);
        Document ownerIdCardDoc = ctx.ownerIdCardDoc().orElse(null);
        Document shipperBizRegDoc = ctx.shipperBizRegDoc().orElse(null);
        RegistrationDocument registrationDoc = findLatestRegistrationDocument(companyId, vehicleId).orElse(null);

        S3ObjectReader.S3ObjectMeta deregistrationMeta = null;
        boolean deregistrationAvailable = false;
        if (deregistrationDoc != null && !isBlank(deregistrationDoc.getS3Key())) {
            deregistrationMeta = s3Reader.readMeta(deregistrationDoc.getS3Key());
            deregistrationAvailable = (deregistrationMeta != null);
        }

        // 차주 신분증 (선택사항)
        S3ObjectReader.S3ObjectMeta ownerIdCardMeta = null;
        boolean ownerIdCardAvailable = false;
        if (ownerIdCardDoc != null && !isBlank(ownerIdCardDoc.getS3Key())) {
            ownerIdCardMeta = s3Reader.readMeta(ownerIdCardDoc.getS3Key());
            ownerIdCardAvailable = (ownerIdCardMeta != null);
        }

        // 대표자 신분증 (화주)
        Document shipperIdCardDoc = ctx.shipperIdCardDoc().orElse(null);
        S3ObjectReader.S3ObjectMeta shipperIdCardMeta = null;
        boolean shipperIdCardAvailable = false;
        if (shipperIdCardDoc != null && !isBlank(shipperIdCardDoc.getS3Key())) {
            shipperIdCardMeta = s3Reader.readMeta(shipperIdCardDoc.getS3Key());
            shipperIdCardAvailable = (shipperIdCardMeta != null);
        }

        // 사업자등록증 (화주)
        S3ObjectReader.S3ObjectMeta shipperBizRegMeta = null;
        boolean shipperBizRegAvailable = false;
        if (shipperBizRegDoc != null && !isBlank(shipperBizRegDoc.getS3Key())) {
            shipperBizRegMeta = s3Reader.readMeta(shipperBizRegDoc.getS3Key());
            shipperBizRegAvailable = (shipperBizRegMeta != null);
        }

        List<String> missingData = List.copyOf(ctx.missingData());
        List<String> missingDocuments = new ArrayList<>(ctx.missingDocuments());
        if (deregistrationDoc != null && !isBlank(deregistrationDoc.getS3Key()) && !deregistrationAvailable) {
            missingDocuments.add("Deregistration source file (S3)");
        }
        if (shipperIdCardDoc != null && !isBlank(shipperIdCardDoc.getS3Key()) && !shipperIdCardAvailable) {
            missingDocuments.add("Shipper representative ID card source file (S3)");
        }
        if (shipperBizRegDoc != null && !isBlank(shipperBizRegDoc.getS3Key()) && !shipperBizRegAvailable) {
            missingDocuments.add("Shipper business registration source file (S3)");
        }

        String deregCacheKey = deregistrationAppCacheKey(
                companyId, vehicle, vin, ownerIdCardDoc, shipperBizRegDoc, registrationDoc
        );
        String invoiceCacheKey = cacheKey(companyId, vehicle.getId(), INVOICE_CACHE_DOC_TYPE, vin);
        String ownerBizCombinedCacheKey = ownerBizCombinedCacheKey(
                companyId, vehicle.getId(), vin, shipperIdCardDoc, shipperBizRegDoc
        );
        S3ObjectReader.S3ObjectMeta deregMeta = s3Reader.readMeta(deregCacheKey);
        S3ObjectReader.S3ObjectMeta invoiceMeta = s3Reader.readMeta(invoiceCacheKey);
        S3ObjectReader.S3ObjectMeta ownerBizCombinedMeta = s3Reader.readMeta(ownerBizCombinedCacheKey);
        if (ownerBizCombinedMeta == null && shipperIdCardAvailable && shipperBizRegAvailable) {
            try {
                getOwnerIdAndShipperBizCombinedPdf(companyId, vehicle, vin);
                ownerBizCombinedMeta = s3Reader.readMeta(ownerBizCombinedCacheKey);
            } catch (Exception e) {
                log.warn("[MalsoPrintService] failed to prebuild rep-id+biz combined pdf vehicleId={}", vehicleId, e);
            }
        }
        boolean ownerBizCombinedAvailable = ownerBizCombinedMeta != null || (shipperIdCardAvailable && shipperBizRegAvailable);

        List<PrintItem> items = new ArrayList<>();

        // 0) 완료 차량이면 실제 말소증을 최상단에 노출
        if (deregistrationDoc != null) {
            items.add(new PrintItem(
                    deregistrationDoc.getId(),
                    "Deregistration Certificate",
                    DocumentType.DEREGISTRATION.name(),
                    KEY_DEREGISTRATION,
                    deregistrationAvailable ? deregistrationDoc.getS3Key() : null,
                    deregistrationAvailable ? s3Upload.toUrl(deregistrationDoc.getS3Key()) : null,
                    buildDeregistrationFilename(vin, deregistrationDoc.getOriginalFilename()),
                    !deregistrationAvailable ? null : (deregistrationDoc.getSizeBytes() != null ? deregistrationDoc.getSizeBytes() : deregistrationMeta.sizeBytes()),
                    !deregistrationAvailable ? null : deregistrationDoc.getUploadedAt(),
                    !deregistrationAvailable ? "application/octet-stream"
                            : nvl(deregistrationDoc.getContentType(), nvl(deregistrationMeta.contentType(), "application/octet-stream")),
                    deregistrationAvailable
            ));
        }

        // 1) 말소등록신청서 (말소증이 있으면 두 번째)
        items.add(new PrintItem(
                VIRTUAL_DEREGISTRATION_APP_DOC_ID,
                "Deregistration Application",
                "DEREGISTRATION_APP",
                KEY_DEREGISTRATION_APP,
                deregMeta == null ? null : deregCacheKey,
                deregMeta == null ? null : s3Upload.toUrl(deregCacheKey),
                FILE_DEREG_APP_PREFIX + vin + ".pdf",
                deregMeta == null ? null : deregMeta.sizeBytes(),
                deregMeta == null ? null : deregMeta.lastModified(),
                deregMeta == null ? "application/pdf" : nvl(deregMeta.contentType(), "application/pdf"),
                deregMeta != null
        ));

        // 2) 대표자신분증+사업자등록증(화주) 합본
        items.add(new PrintItem(
                VIRTUAL_OWNER_BIZ_COMBINED_DOC_ID,
                "Representative ID + Shipper BizReg",
                "OWNER_ID_BIZ_REG_COMBINED",
                KEY_OWNER_BIZ_COMBINED,
                ownerBizCombinedMeta == null ? null : ownerBizCombinedCacheKey,
                ownerBizCombinedMeta == null ? null : s3Upload.toUrl(ownerBizCombinedCacheKey),
                FILE_OWNER_BIZ_PREFIX + vin + ".pdf",
                ownerBizCombinedMeta == null ? null : ownerBizCombinedMeta.sizeBytes(),
                ownerBizCombinedMeta == null ? null : ownerBizCombinedMeta.lastModified(),
                ownerBizCombinedMeta == null ? "application/pdf" : nvl(ownerBizCombinedMeta.contentType(), "application/pdf"),
                ownerBizCombinedAvailable
        ));

        // 3) Invoice / Packing List (금액 입력 후 생성)
        items.add(new PrintItem(
                VIRTUAL_INVOICE_DOC_ID,
                "Invoice/PackingList",
                "INVOICE",
                KEY_INVOICE,
                invoiceMeta == null ? null : invoiceCacheKey,
                invoiceMeta == null ? null : s3Upload.toUrl(invoiceCacheKey),
                "Invoice_PackingList_" + vin + ".pdf",
                invoiceMeta == null ? null : invoiceMeta.sizeBytes(),
                invoiceMeta == null ? null : invoiceMeta.lastModified(),
                invoiceMeta == null ? "application/pdf" : nvl(invoiceMeta.contentType(), "application/pdf"),
                invoiceMeta != null
        ));

        // 4) 차주 신분증 (선택사항 — 제일 마지막)
        items.add(new PrintItem(
                ownerIdCardDoc == null ? null : ownerIdCardDoc.getId(),
                "Owner ID Card",
                DocumentType.ID_CARD.name(),
                KEY_ID_CARD,
                ownerIdCardAvailable ? ownerIdCardDoc.getS3Key() : null,
                ownerIdCardAvailable ? s3Upload.toUrl(ownerIdCardDoc.getS3Key()) : null,
                buildOwnerIdCardFilename(vin, ownerIdCardDoc == null ? null : ownerIdCardDoc.getOriginalFilename()),
                !ownerIdCardAvailable ? null : (ownerIdCardDoc.getSizeBytes() != null ? ownerIdCardDoc.getSizeBytes() : ownerIdCardMeta.sizeBytes()),
                !ownerIdCardAvailable ? null : ownerIdCardDoc.getUploadedAt(),
                !ownerIdCardAvailable ? "application/octet-stream"
                        : nvl(ownerIdCardDoc.getContentType(), nvl(ownerIdCardMeta.contentType(), "application/octet-stream")),
                ownerIdCardAvailable
        ));

        return new PrintItemsResponse(
                vehicleId,
                vin,
                vehicle.getOwnerType() == null ? null : vehicle.getOwnerType().name(),
                items,
                missingData,
                List.copyOf(missingDocuments),
                vehicle.getPurchasePrice(),
                invoiceMeta != null
        );
    }

    /**
     * 출력 문서를 사전 준비합니다.
     * - 차량 등록 직후 생성 대상: deregistration_app
     * - invoice는 금액 입력 단계에서 별도 생성
     */
    @Transactional
    public PrintItemsResponse prepareItems(Long companyId, Long vehicleId) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();

        // 차량등록 완료 시점에 미리 생성할 기본 문서
        getDeregistrationPdf(companyId, vehicle, vin);
        boolean canPrebuildOwnerBizCombined = ctx.shipperIdCardDoc().isPresent()
                && ctx.shipperBizRegDoc().isPresent()
                && !isBlank(ctx.shipperIdCardDoc().get().getS3Key())
                && !isBlank(ctx.shipperBizRegDoc().get().getS3Key());
        if (canPrebuildOwnerBizCombined) {
            getOwnerIdAndShipperBizCombinedPdf(companyId, vehicle, vin);
        }

        return getItems(companyId, vehicleId);
    }

    /**
     * Invoice/PackingList 생성.
     * amount가 없으면 랜덤 값을 적용해 생성합니다.
     */
    @Transactional
    public InvoicePrepareResponse prepareInvoice(Long companyId, Long vehicleId, Long amount) {
        Vehicle vehicle = loadVehicle(companyId, vehicleId);
        String vin = safeVin(vehicle);

        long resolvedAmount = resolveInvoiceAmount(amount);
        boolean autoFilled = amount == null || amount <= 0;
        String message = autoFilled
                ? "Amount not provided. Generated with a random amount."
                : "Invoice/PackingList generated with the provided amount.";

        vehicle.updatePurchasePrice(resolvedAmount);
        vehicleRepo.save(vehicle);

        getInvoicePdf(companyId, vehicle, vin, resolvedAmount, true);
        PrintItemsResponse items = getItems(companyId, vehicleId);
        return new InvoicePrepareResponse(
                vehicleId,
                resolvedAmount,
                autoFilled,
                message,
                items
        );
    }

    /**
     * 개별 문서 PDF 바이트 조회.
     * 캐시가 있으면 S3에서 반환, 없으면 생성 후 캐시합니다.
     */
    @Transactional(readOnly = true)
    public DocumentBytes getDocument(Long companyId, Long vehicleId, String documentKey) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();

        return switch (documentKey) {
            case KEY_DEREGISTRATION -> getDeregistrationDocument(companyId, vehicle, vin);
            case KEY_DEREGISTRATION_APP -> getDeregistrationPdf(companyId, vehicle, vin);
            case KEY_ID_CARD -> getOwnerIdCardDocument(companyId, vehicle, vin);
            case KEY_OWNER_BIZ_COMBINED -> getOwnerIdAndShipperBizCombinedPdf(companyId, vehicle, vin);
            case KEY_INVOICE -> getInvoicePdfCached(companyId, vehicle, vin);
            default -> throw new CustomException(ErrorCode.INVALID_INPUT);
        };
    }

    /**
     * 전체 문서를 ZIP 번들로 반환.
     */
    @Transactional(readOnly = true)
    public byte[] buildBundle(Long companyId, Long vehicleId) {
        return buildBundle(companyId, vehicleId, null);
    }

    @Transactional(readOnly = true)
    public byte[] buildBundle(Long companyId, Long vehicleId, List<String> selectedKeys) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();
        Set<String> keys = normalizeSelectedKeys(selectedKeys, ctx.deregistrationDoc().isPresent());

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            if (keys.contains(KEY_DEREGISTRATION)) {
                DocumentBytes deregistrationDoc = getDeregistrationDocument(companyId, vehicle, vin);
                if (deregistrationDoc.data() != null) {
                    putEntry(zos, deregistrationDoc.filename(), deregistrationDoc.data());
                }
            }

            if (keys.contains(KEY_DEREGISTRATION_APP)) {
                DocumentBytes deregPdf = getDeregistrationPdf(companyId, vehicle, vin);
                if (deregPdf.data() != null) {
                    putEntry(zos, deregPdf.filename(), deregPdf.data());
                }
            }

            if (keys.contains(KEY_OWNER_BIZ_COMBINED)) {
                DocumentBytes combined = getOwnerIdAndShipperBizCombinedPdf(companyId, vehicle, vin);
                if (combined.data() != null) {
                    putEntry(zos, combined.filename(), combined.data());
                }
            }

            if (keys.contains(KEY_INVOICE)) {
                DocumentBytes invoicePdf = getInvoicePdfCached(companyId, vehicle, vin);
                if (invoicePdf.data() != null) {
                    putEntry(zos, invoicePdf.filename(), invoicePdf.data());
                }
            }

            // 차주 신분증 (선택사항 — 맨 마지막)
            if (keys.contains(KEY_ID_CARD)) {
                DocumentBytes ownerIdCard = getOwnerIdCardDocument(companyId, vehicle, vin);
                putEntry(zos, ownerIdCard.filename(), ownerIdCard.data());
            }

            zos.finish();
            return bos.toByteArray();

        } catch (IOException e) {
            log.error("[MalsoPrintService] bundle build failed vehicleId={}", vehicleId, e);
            throw new IllegalStateException("Failed to build ZIP bundle", e);
        }
    }

    /**
     * 전체출력용: 모든 문서를 하나의 PDF로 합본하여 반환.
     * 프론트에서 이 PDF를 새 탭으로 열어 window.print() 호출.
     *
     * <p>합본 순서:
     * 1) 말소증 PDF (완료 차량에서만)
     * 2) 말소등록신청서 PDF
     * 3) 소유자 신분증 (이미지면 PDF 페이지로 변환)
     * 4) 대표자신분증+사업자등록증(화주) PDF
     * 5) Invoice/PackingList PDF
     */
    @Transactional(readOnly = true)
    public byte[] buildMergedPdf(Long companyId, Long vehicleId) {
        return buildMergedPdf(companyId, vehicleId, null);
    }

    @Transactional(readOnly = true)
    public byte[] buildMergedPdf(Long companyId, Long vehicleId, List<String> selectedKeys) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();
        Set<String> keys = normalizeSelectedKeys(selectedKeys, ctx.deregistrationDoc().isPresent());

        // 캐시 확인 (선택 키 + 소스 문서 스탬프 기반)
        String cacheKey = mergedPdfCacheKey(
                companyId,
                vehicle,
                vin,
                keys,
                ctx.deregistrationDoc().orElse(null),
                ctx.ownerIdCardDoc().orElse(null),
                ctx.shipperIdCardDoc().orElse(null),
                ctx.shipperBizRegDoc().orElse(null)
        );
        byte[] cached = s3Reader.readBytes(cacheKey);
        if (cached != null) return cached;

        List<byte[]> pdfPages = new ArrayList<>();

        if (keys.contains(KEY_DEREGISTRATION)) {
            DocumentBytes deregistrationDoc = getDeregistrationDocument(companyId, vehicle, vin);
            if (deregistrationDoc.data() != null) {
                pdfPages.add(deregistrationDoc.data());
            }
        }

        if (keys.contains(KEY_DEREGISTRATION_APP)) {
            DocumentBytes deregPdf = getDeregistrationPdf(companyId, vehicle, vin);
            if (deregPdf.data() != null) {
                pdfPages.add(deregPdf.data());
            }
        }

        if (keys.contains(KEY_OWNER_BIZ_COMBINED)) {
            DocumentBytes combined = getOwnerIdAndShipperBizCombinedPdf(companyId, vehicle, vin);
            if (combined.data() != null) {
                pdfPages.add(combined.data());
            }
        }

        if (keys.contains(KEY_INVOICE)) {
            DocumentBytes invoicePdf = getInvoicePdfCached(companyId, vehicle, vin);
            if (invoicePdf.data() != null) {
                pdfPages.add(invoicePdf.data());
            }
        }

        // 차주 신분증 (선택사항 — 맨 마지막)
        if (keys.contains(KEY_ID_CARD)) {
            DocumentBytes ownerIdCard = getOwnerIdCardDocument(companyId, vehicle, vin);
            pdfPages.add(toPdf(ownerIdCard));
        }

        if (pdfPages.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }

        byte[] merged = mergePdfs(pdfPages);

        // 캐시 저장
        cacheToS3(cacheKey, merged, "application/pdf");

        return merged;
    }

    /**
     * 이미지 파일(PNG/JPG)이면 PDF 한 페이지로 변환, 이미 PDF면 그대로 반환.
     */
    private byte[] toPdf(DocumentBytes doc) {
        if (doc.contentType() != null && doc.contentType().contains("pdf")) {
            return doc.data();
        }

        // 이미지 → PDF 변환
        try (PDDocument pdfDoc = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromByteArray(pdfDoc, doc.data(), doc.filename());

            float imgWidth = image.getWidth();
            float imgHeight = image.getHeight();

            // A4 기준으로 이미지를 fit
            PDRectangle pageSize = PDRectangle.A4;
            float scale = Math.min(pageSize.getWidth() / imgWidth, pageSize.getHeight() / imgHeight);
            float scaledW = imgWidth * scale;
            float scaledH = imgHeight * scale;

            PDPage page = new PDPage(pageSize);
            pdfDoc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(pdfDoc, page)) {
                float x = (pageSize.getWidth() - scaledW) / 2;
                float y = (pageSize.getHeight() - scaledH) / 2;
                cs.drawImage(image, x, y, scaledW, scaledH);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdfDoc.save(out);
            return out.toByteArray();

        } catch (IOException e) {
            log.warn("[MalsoPrintService] image-to-pdf conversion failed: {}", doc.filename(), e);
            return doc.data(); // 변환 실패 시 원본 반환 (프론트에서 처리)
        }
    }

    /**
     * 여러 PDF 바이트를 하나로 합본.
     */
    private byte[] mergePdfs(List<byte[]> pdfBytesList) {
        PDFMergerUtility merger = new PDFMergerUtility();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        merger.setDestinationStream(out);

        for (byte[] pdfBytes : pdfBytesList) {
            merger.addSource(new ByteArrayInputStream(pdfBytes));
        }

        try {
            merger.mergeDocuments(null);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("[MalsoPrintService] PDF merge failed", e);
            throw new IllegalStateException("Failed to merge PDF files", e);
        }
    }

    /**
     * PDF를 A4 내부 여백에 맞춰 다시 배치해 프린터 비인쇄영역으로 인한 가장자리 잘림을 완화합니다.
     * 말소신청서에만 적용합니다.
     */
    private byte[] fitPdfToA4WithMargin(byte[] sourcePdf, float marginPt) {
        if (sourcePdf == null || sourcePdf.length == 0) {
            return sourcePdf;
        }

        try (PDDocument src = PDDocument.load(sourcePdf);
             PDDocument dst = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFRenderer renderer = new PDFRenderer(src);

            for (int i = 0; i < src.getNumberOfPages(); i++) {
                PDPage srcPage = src.getPage(i);
                PDRectangle sourceBox = srcPage.getMediaBox();
                PDRectangle target = sourceBox.getWidth() > sourceBox.getHeight()
                        ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                        : PDRectangle.A4;

                BufferedImage rendered = renderer.renderImageWithDPI(i, 220, ImageType.RGB);
                BufferedImage image = cropWhiteMargin(rendered);
                PDImageXObject xImage = LosslessFactory.createFromImage(dst, image);

                float maxW = target.getWidth() - (marginPt * 2f);
                float maxH = target.getHeight() - (marginPt * 2f);
                float scale = Math.min(maxW / image.getWidth(), maxH / image.getHeight());
                float drawW = image.getWidth() * scale;
                float drawH = image.getHeight() * scale;
                float x = (target.getWidth() - drawW) / 2f;
                float y = (target.getHeight() - drawH) / 2f;

                PDPage outPage = new PDPage(target);
                dst.addPage(outPage);
                try (PDPageContentStream cs = new PDPageContentStream(dst, outPage)) {
                    cs.drawImage(xImage, x, y, drawW, drawH);
                }
            }

            dst.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("[MalsoPrintService] failed to normalize pdf for print", e);
            return sourcePdf;
        }
    }

    /**
     * Crop large white area around content so tiny top-left output gets scaled properly on A4.
     */
    private BufferedImage cropWhiteMargin(BufferedImage source) {
        java.awt.Rectangle bounds = findContentBounds(source);
        if (bounds == null) {
            return source;
        }
        return source.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    private java.awt.Rectangle findContentBounds(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                if (!isNearWhite(rgb)) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return null;
        }

        int pad = 8;
        int x = Math.max(0, minX - pad);
        int y = Math.max(0, minY - pad);
        int w = Math.min(width - x, (maxX - minX + 1) + (pad * 2));
        int h = Math.min(height - y, (maxY - minY + 1) + (pad * 2));
        return new java.awt.Rectangle(x, y, w, h);
    }

    private boolean isNearWhite(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= 245 && g >= 245 && b >= 245;
    }

    // ================== 개별 문서 생성 ==================

    private DocumentBytes getDeregistrationDocument(Long companyId, Vehicle vehicle, String vin) {
        Document deregistrationDoc = findLatestDeregistrationDocument(companyId, vehicle.getId())
                .orElseThrow(() -> new CustomException(
                        ErrorCode.REQUIRED_DOCUMENT_MISSING,
                        "Deregistration document (DEREGISTRATION) is required for completed vehicles."
                ));

        byte[] data = s3Reader.readBytes(deregistrationDoc.getS3Key());
        if (data == null || data.length == 0) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DOCUMENT_MISSING,
                    "Unable to read deregistration source file."
            );
        }

        byte[] pdfBytes = isPdf(deregistrationDoc)
                ? data
                : toPdf(new DocumentBytes(
                        buildDeregistrationFilename(vin, deregistrationDoc.getOriginalFilename()),
                        deregistrationDoc.getContentType(),
                        data
                ));

        return new DocumentBytes(FILE_DEREG_CERT_PREFIX + vin + ".pdf", "application/pdf", pdfBytes);
    }

    private DocumentBytes getOwnerIdCardDocument(Long companyId, Vehicle vehicle, String vin) {
        Document ownerIdCardDoc = findLatestOwnerIdCardDocument(companyId, vehicle.getId())
                .orElseThrow(() -> new CustomException(
                        ErrorCode.REQUIRED_DOCUMENT_MISSING,
                        "Required document missing: owner ID card (ID_CARD)."
                ));

        byte[] data = s3Reader.readBytes(ownerIdCardDoc.getS3Key());
        if (data == null || data.length == 0) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DOCUMENT_MISSING,
                    "Required document data missing: unable to read owner ID card source file."
            );
        }

        byte[] pdfBytes = isPdf(ownerIdCardDoc)
                ? data
                : toPdf(new DocumentBytes(
                        buildOwnerIdCardFilename(vin, ownerIdCardDoc.getOriginalFilename()),
                        ownerIdCardDoc.getContentType(),
                        data
                ));

        return new DocumentBytes(FILE_OWNER_ID_PREFIX + vin + ".pdf", "application/pdf", pdfBytes);
    }

    private DocumentBytes getOwnerIdAndShipperBizCombinedPdf(Long companyId, Vehicle vehicle, String vin) {
        if (vehicle.getShipperId() == null) {
            throw new CustomException(ErrorCode.REQUIRED_DATA_MISSING, "Required data missing: shipper information (shipperId).");
        }

        // ── 합본 vs 개별(대표자 신분증+사업자등록증) 중 최신 것 우선 ──
        Optional<Document> combinedDoc = findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.BIZ_ID_COMBINED);
        Optional<Document> shipperIdCardDoc = findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.ID_CARD);
        Optional<Document> shipperBizRegDoc = findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.BIZ_REGISTRATION);

        boolean hasCombined = combinedDoc.isPresent() && !isBlank(combinedDoc.get().getS3Key());
        boolean hasIndividual = shipperIdCardDoc.isPresent() && !isBlank(shipperIdCardDoc.get().getS3Key())
                && shipperBizRegDoc.isPresent() && !isBlank(shipperBizRegDoc.get().getS3Key());

        // 둘 다 있으면 최신 업로드 시점 비교 (합본 uploadedAt vs 개별 중 더 나중 것)
        boolean useCombined = hasCombined;
        if (hasCombined && hasIndividual) {
            java.time.Instant combinedAt = combinedDoc.get().getUploadedAt();
            java.time.Instant idCardAt = shipperIdCardDoc.get().getUploadedAt();
            java.time.Instant bizRegAt = shipperBizRegDoc.get().getUploadedAt();
            java.time.Instant individualLatest = (idCardAt != null && bizRegAt != null)
                    ? (idCardAt.isAfter(bizRegAt) ? idCardAt : bizRegAt)
                    : (idCardAt != null ? idCardAt : bizRegAt);
            if (combinedAt != null && individualLatest != null && individualLatest.isAfter(combinedAt)) {
                useCombined = false;
                log.info("[MalsoPrintService] 개별 문서가 합본보다 최신 → 개별 합성 사용 (합본={}, 개별={})", combinedAt, individualLatest);
            }
        }

        if (useCombined && combinedDoc.isPresent() && !isBlank(combinedDoc.get().getS3Key())) {
            byte[] combinedBytes = s3Reader.readBytes(combinedDoc.get().getS3Key());
            if (combinedBytes != null && combinedBytes.length > 0) {
                // 이미지면 PDF로 변환
                String ct = combinedDoc.get().getContentType();
                if (ct != null && ct.startsWith("image/")) {
                    try (PDDocument pdfDoc = new PDDocument();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        PDImageXObject pdImage = PDImageXObject.createFromByteArray(pdfDoc, combinedBytes, "combined");
                        float imgW = pdImage.getWidth();
                        float imgH = pdImage.getHeight();
                        PDRectangle pageSize = new PDRectangle(PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                        float scale = Math.min(pageSize.getWidth() / imgW, pageSize.getHeight() / imgH);
                        float sw = imgW * scale;
                        float sh = imgH * scale;
                        PDPage page = new PDPage(pageSize);
                        pdfDoc.addPage(page);
                        try (PDPageContentStream cs = new PDPageContentStream(pdfDoc, page)) {
                            cs.drawImage(pdImage, (pageSize.getWidth() - sw) / 2, (pageSize.getHeight() - sh) / 2, sw, sh);
                        }
                        pdfDoc.save(baos);
                        combinedBytes = baos.toByteArray();
                    } catch (Exception e) {
                        log.warn("[MalsoPrintService] 합본 이미지→PDF 변환 실패", e);
                    }
                }
                return new DocumentBytes(FILE_OWNER_BIZ_PREFIX + vin + ".pdf", "application/pdf", combinedBytes);
            }
            // 합본 문서가 존재하지만 S3에서 읽기 실패 → 개별 문서가 없을 수 있으므로 여기서 처리
            if (!hasIndividual) {
                throw new CustomException(
                        ErrorCode.REQUIRED_DOCUMENT_MISSING,
                        "합본 문서(대표자 신분증+사업자등록증)의 파일을 읽을 수 없습니다. 문서를 다시 업로드해주세요."
                );
            }
        }

        // ── 기존 로직: 대표자 신분증(화주) + 사업자등록증 합성 ──
        Document idDoc = shipperIdCardDoc.orElseThrow(() -> new CustomException(
                ErrorCode.REQUIRED_DOCUMENT_MISSING,
                "Required document missing: shipper representative ID card (대표자 신분증) required for combined document generation."
        ));
        Document bizDoc = shipperBizRegDoc.orElseThrow(() -> new CustomException(
                ErrorCode.REQUIRED_DOCUMENT_MISSING,
                "Required document missing: shipper business registration (BIZ_REGISTRATION) required for combined document generation."
        ));
        String cacheKey = ownerBizCombinedCacheKey(
                companyId, vehicle.getId(), vin, idDoc, bizDoc
        );
        byte[] cached = s3Reader.readBytes(cacheKey);
        if (cached != null && cached.length > 0) {
            return new DocumentBytes(FILE_OWNER_BIZ_PREFIX + vin + ".pdf", "application/pdf", cached);
        }

        byte[] ownerIdBytes = s3Reader.readBytes(idDoc.getS3Key());
        if (ownerIdBytes == null || ownerIdBytes.length == 0) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DOCUMENT_MISSING,
                    "Required document data missing: unable to read shipper representative ID card source file."
            );
        }
        byte[] shipperBizRegBytes = s3Reader.readBytes(bizDoc.getS3Key());
        if (shipperBizRegBytes == null || shipperBizRegBytes.length == 0) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DOCUMENT_MISSING,
                    "Required document data missing: unable to read shipper business registration source file."
            );
        }

        BufferedImage bizRegImage = readFirstPageAsImage(shipperBizRegBytes, bizDoc);
        BufferedImage ownerIdImage = readFirstPageAsImage(ownerIdBytes, idDoc);
        if (bizRegImage == null || ownerIdImage == null) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DOCUMENT_MISSING,
                    "Failed to generate owner ID + shipper business registration document."
            );
        }

        byte[] composedPdf = composeOwnerIdOnBizRegPdf(bizRegImage, ownerIdImage, vin);
        cacheToS3(cacheKey, composedPdf, "application/pdf");
        return new DocumentBytes(FILE_OWNER_BIZ_PREFIX + vin + ".pdf", "application/pdf", composedPdf);
    }

    private DocumentBytes getDeregistrationPdf(Long companyId, Vehicle vehicle, String vin) {
        Document ownerIdCardDoc = findLatestOwnerIdCardDocument(companyId, vehicle.getId()).orElse(null);
        Document shipperBizRegDoc = findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.BIZ_REGISTRATION)
                .orElse(null);
        RegistrationDocument registrationDoc = findLatestRegistrationDocument(companyId, vehicle.getId()).orElse(null);
        String cacheKey = deregistrationAppCacheKey(
                companyId, vehicle, vin, ownerIdCardDoc, shipperBizRegDoc, registrationDoc
        );

        // 캐시 확인
        byte[] cached = s3Reader.readBytes(cacheKey);
        if (cached != null) {
            String filename = FILE_DEREG_APP_PREFIX + vin + ".pdf";
            return new DocumentBytes(filename, "application/pdf", cached);
        }

        // 단일 경로: v3 XLSX 템플릿 채움 -> PDF 변환
        // 구버전 직접 PDF 생성기는 사용하지 않습니다.
        MalsoXlsxData data = buildMalsoData(companyId, vehicle);
        byte[] xlsx = malsoXlsxGenerator.generate(data);
        byte[] pdf = pdfConverter.convert(xlsx);

        // 캐시 저장
        cacheToS3(cacheKey, pdf, "application/pdf");

        String filename = FILE_DEREG_APP_PREFIX + vin + ".pdf";
        return new DocumentBytes(filename, "application/pdf", pdf);
    }

    private DocumentBytes getInvoicePdf(Long companyId,
                                        Vehicle vehicle,
                                        String vin,
                                        Long overrideAmount,
                                        boolean forceRegenerate) {
        String cacheKey = cacheKey(companyId, vehicle.getId(), INVOICE_CACHE_DOC_TYPE, vin);

        if (!forceRegenerate) {
            byte[] cached = s3Reader.readBytes(cacheKey);
            if (cached != null) {
                String filename = "Invoice_PackingList_" + vin + ".pdf";
                return new DocumentBytes(filename, "application/pdf", cached);
            }
        }

        // 생성
        String invoiceNo = resolveVehicleInvoiceNo(companyId, vehicle, true);
        byte[] xlsx = generateMalsoInvoiceXlsx(companyId, vehicle, overrideAmount, invoiceNo);
        byte[] pdf = pdfConverter.convert(xlsx);

        // 캐시 저장
        cacheToS3(cacheKey, pdf, "application/pdf");

        String filename = "Invoice_PackingList_" + vin + ".pdf";
        return new DocumentBytes(filename, "application/pdf", pdf);
    }

    private DocumentBytes getInvoicePdfCached(Long companyId, Vehicle vehicle, String vin) {
        String cacheKey = cacheKey(companyId, vehicle.getId(), INVOICE_CACHE_DOC_TYPE, vin);
        byte[] cached = s3Reader.readBytes(cacheKey);
        if (cached == null) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DATA_MISSING,
                    "Please enter amount first and generate Invoice/PackingList."
            );
        }
        String filename = "Invoice_PackingList_" + vin + ".pdf";
        return new DocumentBytes(filename, "application/pdf", cached);
    }

    /**
     * 말소등록신청서 XLSX 다운로드 (편집 가능 원본).
     */
    @Transactional(readOnly = true)
    public DocumentBytes getDeregistrationXlsx(Long companyId, Long vehicleId) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();

        MalsoXlsxData data = buildMalsoData(companyId, vehicle);
        byte[] xlsx = malsoXlsxGenerator.generate(data);

        String filename = FILE_DEREG_APP_PREFIX + vin + ".xlsx";
        return new DocumentBytes(filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
    }

    /**
     * Invoice XLSX 다운로드 (편집 가능 원본).
     */
    @Transactional(readOnly = true)
    public DocumentBytes getInvoiceXlsx(Long companyId, Long vehicleId) {
        PrintContext ctx = loadPrintContext(companyId, vehicleId);
        Vehicle vehicle = ctx.vehicle();
        String vin = ctx.vin();

        String invoiceNo = firstNonBlank(vehicle.getExportInvoiceNo(), "AUTO");
        byte[] xlsx = generateMalsoInvoiceXlsx(companyId, vehicle, null, invoiceNo);

        String filename = "Invoice_PackingList_" + vin + ".xlsx";
        return new DocumentBytes(filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
    }

    /**
     * 전체출력 이력 기록.
     * 최초 전체출력 시 Vehicle.malsoPrintedAt을 기록하여 상태를 대기 → 진행으로 전환합니다.
     */
    @Transactional
    public void markPrinted(Long companyId, Long vehicleId) {
        Vehicle vehicle = loadVehicle(companyId, vehicleId);
        vehicle.markMalsoPrinted();
        vehicleRepo.save(vehicle);
    }

    /**
     * 캐시 무효화 (차량 정보가 변경되면 호출).
     * 다음 요청 시 다시 생성됩니다.
     */
    public void invalidateCache(Long companyId, Long vehicleId) {
        log.info("[MalsoPrintService] cache invalidated companyId={} vehicleId={}", companyId, vehicleId);
    }

    // ================== 내부 메서드 ==================

    private MalsoXlsxData buildMalsoData(Long companyId, Vehicle vehicle) {
        // 소유자 정보 우선순위:
        // 1) 소유자 신분증 OCR
        // 2) 자동차등록증 OCR
        // 3) 차량 엔티티 저장값
        OwnerInfo ownerInfo = resolveOwnerInfo(companyId, vehicle);
        String ownerName = ownerInfo.name();
        String ownerIdNo = ownerInfo.idNo();
        String ownerAddress = ownerInfo.address();

        // 화주(위임자) 정보
        String poaName = "";
        String poaRepresentativeName = "";
        String poaBizNo = "";
        String poaAddress = "";
        // 말소등록신청서 위임장 영역은 요청사항에 따라 서명 이미지를 넣지 않는다.
        byte[] signBytes = null;

        if (vehicle.getShipperId() != null) {
            Shipper shipper = shipperRepo.findByIdAndCompanyId(vehicle.getShipperId(), companyId)
                    .orElse(null);
            if (shipper != null) {
                // 상호: 사업자등록증 companyName 우선, 없으면 화주 이름
                poaName = nvl(
                        resolveShipperCompanyName(companyId, vehicle.getShipperId(), shipper),
                        nvl(shipper.getName(), "")
                );
                poaRepresentativeName = nvl(
                        resolveShipperRepresentativeName(companyId, vehicle.getShipperId(), shipper),
                        nvl(shipper.getName(), "")
                );
                poaBizNo = nvl(resolveShipperBusinessNumber(companyId, vehicle.getShipperId(), shipper), "");
                poaAddress = nvl(resolveShipperAddress(companyId, vehicle.getShipperId(), shipper), "");

                // intentionally no sign image for malso deregistration application
            }
        }

        return new MalsoXlsxData(
                nvl(ownerName, ""),
                nvl(ownerIdNo, ""),
                nvl(ownerAddress, ""),
                nvl(vehicle.getVehicleNo(), ""),
                nvl(vehicle.getVin(), ""),
                vehicle.getMileageKm(),
                "",                // 신청인 성명은 비움
                "",                // 신청인 주민등록번호는 비움
                poaName,           // 위임자 성명
                poaRepresentativeName, // 위임장 대표자 성명
                poaBizNo,          // 위임자 사업자번호
                poaAddress,        // 위임자 주소
                signBytes          // 서명/인 이미지
        );
    }

    private OwnerInfo resolveOwnerInfo(Long companyId, Vehicle vehicle) {
        Optional<OwnerInfo> ownerIdCardInfo = resolveOwnerInfoFromOwnerIdCard(companyId, vehicle.getId());
        Optional<OwnerInfo> registrationInfo = resolveOwnerInfoFromRegistration(companyId, vehicle.getId());

        String ownerName = firstNonBlank(
                ownerIdCardInfo.map(OwnerInfo::name).orElse(null),
                registrationInfo.map(OwnerInfo::name).orElse(null),
                vehicle.getOwnerName()
        );
        String ownerIdNo = firstNonBlank(
                sanitizeOwnerIdForApplication(ownerIdCardInfo.map(OwnerInfo::idNo).orElse(null)),
                registrationInfo.map(OwnerInfo::idNo).orElse(null),
                vehicle.getOwnerId()
        );
        String ownerAddress = firstNonBlank(
                ownerIdCardInfo.map(OwnerInfo::address).orElse(null),
                registrationInfo.map(OwnerInfo::address).orElse(null)
        );

        return new OwnerInfo(
                trimToNull(ownerName),
                trimToNull(ownerIdNo),
                trimToNull(ownerAddress)
        );
    }

    private Optional<OwnerInfo> resolveOwnerInfoFromOwnerIdCard(Long companyId, Long vehicleId) {
        Optional<Document> ownerIdCardDoc = findLatestOwnerIdCardDocument(companyId, vehicleId);
        if (ownerIdCardDoc.isEmpty() || isBlank(ownerIdCardDoc.get().getS3Key())) {
            return Optional.empty();
        }

        Document doc = ownerIdCardDoc.get();

        // 1) 이미 OCR 처리된 결과(OcrParseJob)가 있으면 그걸 사용
        Optional<OwnerInfo> fromJob = resolveOwnerInfoFromOcrJob(companyId, doc.getId());
        if (fromJob.isPresent()) {
            return fromJob;
        }

        // 2) 없으면 실시간 OCR + 파서 + LLM 보정
        byte[] source = s3Reader.readBytes(doc.getS3Key());
        if (source == null || source.length == 0) {
            log.warn("[MalsoPrintService] owner id-card file is empty. vehicleId={}, docId={}", vehicleId, doc.getId());
            return Optional.empty();
        }

        try {
            String rawJson = upstageService.parseDocuments(
                    companyId,
                    new ByteArrayResource(source),
                    nvl(doc.getOriginalFilename(), "owner-id-card")
            );
            UpstageResponse response = objectMapper.readValue(rawJson, UpstageResponse.class);
            ParsedIdCard parsed = idCardParserService.parse(response);
            // LLM 보정 적용
            parsed = idCardLlmRefiner.refineIfNeeded(parsed, response);

            return Optional.of(new OwnerInfo(
                    trimToNull(parsed.holderName()),
                    trimToNull(parsed.idNumber()),
                    trimToNull(parsed.idAddress())
            ));
        } catch (Exception e) {
            log.warn("[MalsoPrintService] owner id-card OCR fallback failed. vehicleId={}, docId={}", vehicleId, doc.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * 이미 완료된 OCR 작업 결과에서 소유자 정보를 추출.
     */
    private Optional<OwnerInfo> resolveOwnerInfoFromOcrJob(Long companyId, Long documentId) {
        return jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                companyId, documentId, OcrJobStatus.SUCCEEDED
        ).flatMap(job -> {
            try {
                String resultJson = job.getResultJson();
                if (isBlank(resultJson)) return Optional.empty();
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resultJson);
                com.fasterxml.jackson.databind.JsonNode parsed = root.has("parsed") ? root.get("parsed") : root;

                String name = jsonText(parsed, "holderName");
                String idNo = jsonText(parsed, "idNumber");
                String address = jsonText(parsed, "idAddress");

                if (isBlank(name) && isBlank(idNo) && isBlank(address)) {
                    return Optional.empty();
                }
                return Optional.of(new OwnerInfo(trimToNull(name), trimToNull(idNo), trimToNull(address)));
            } catch (Exception e) {
                log.warn("[MalsoPrintService] OCR job 결과 파싱 실패 jobId={}", job.getId(), e);
                return Optional.empty();
            }
        });
    }

    private String jsonText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        String text = node.get(field).asText().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private Optional<OwnerInfo> resolveOwnerInfoFromRegistration(Long companyId, Long vehicleId) {
        return findLatestRegistrationDocument(companyId, vehicleId)
                .map(doc -> new OwnerInfo(
                        trimToNull(doc.getOwnerName()),
                        trimToNull(doc.getOwnerId()),
                        trimToNull(doc.getAddress())
                ));
    }

    private Optional<RegistrationDocument> findLatestRegistrationDocument(Long companyId, Long vehicleId) {
        if (vehicleId == null) {
            return Optional.empty();
        }
        return documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                        companyId, DocumentRefType.VEHICLE, vehicleId, DocumentType.REGISTRATION
                )
                .flatMap(doc -> registrationDocRepo.findByCompanyIdAndId(companyId, doc.getId()));
    }

    private String sanitizeOwnerIdForApplication(String ownerIdNo) {
        return trimToNull(ownerIdNo);
    }

    private String extractUnmaskedOwnerIdNo(UpstageResponse response) {
        if (response == null || response.elements() == null || response.elements().isEmpty()) {
            return null;
        }
        StringBuilder raw = new StringBuilder();
        for (UpstageElement element : response.elements()) {
            if (element == null || element.content() == null) {
                continue;
            }
            if (!isBlank(element.content().text())) {
                raw.append(element.content().text()).append('\n');
            }
            if (!isBlank(element.content().markdown())) {
                raw.append(element.content().markdown()).append('\n');
            }
            if (!isBlank(element.content().html())) {
                raw.append(element.content().html().replaceAll("<[^>]*>", " ")).append('\n');
            }
        }

        Matcher matcher = KOR_ID_PATTERN.matcher(raw.toString());
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "-" + matcher.group(2);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 말소 간이 Invoice/PackingList는 신고필증 RORO 템플릿 생성기를 재사용합니다.
     */
    private byte[] generateMalsoInvoiceXlsx(Long companyId, Vehicle vehicle, Long overrideAmount, String invoiceNo) {
        Long effectiveAmount = resolveInvoiceAmount(overrideAmount != null && overrideAmount > 0
                ? overrideAmount
                : vehicle.getPurchasePrice());
        Vehicle effectiveVehicle = Vehicle.builder()
                .id(vehicle.getId())
                .vin(nvl(vehicle.getVin(), DEFAULT_MALSO_VIN))
                .modelName(nvl(vehicle.getModelName(), DEFAULT_MALSO_MODEL_NAME))
                .modelYear(vehicle.getModelYear() != null ? vehicle.getModelYear() : defaultModelYear())
                .fuelType(nvl(vehicle.getFuelType(), DEFAULT_MALSO_FUEL))
                .displacement(vehicle.getDisplacement() != null ? vehicle.getDisplacement() : DEFAULT_MALSO_ENGINE_CC)
                .shipperId(vehicle.getShipperId())
                .shipperName(nvl(vehicle.getShipperName(), DEFAULT_MALSO_SHIPPER_NAME))
                .ownerType(vehicle.getOwnerType())
                .purchasePrice(effectiveAmount)
                .exportInvoiceNo(firstNonBlank(invoiceNo, vehicle.getExportInvoiceNo(), "AUTO"))
                .build();

        MalsoInvoiceContext invoiceContext = resolveMalsoInvoiceContext(companyId, vehicle);

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(effectiveVehicle.getId())
                .price(effectiveVehicle.getPurchasePrice())
                .tradeCondition(invoiceContext.tradeCondition())
                .build();

        String shipperName = nvl(effectiveVehicle.getShipperName(), DEFAULT_MALSO_SHIPPER_NAME);
        String shipperAddress = DEFAULT_MALSO_SHIPPER_ADDRESS;
        String shipperPhone = DEFAULT_MALSO_SHIPPER_PHONE;

        if (effectiveVehicle.getShipperId() != null) {
            Shipper shipper = shipperRepo.findByIdAndCompanyId(effectiveVehicle.getShipperId(), companyId).orElse(null);
            if (shipper != null) {
                shipperName = nvl(shipper.getName(), shipperName);
                shipperAddress = nvl(resolveShipperAddress(companyId, effectiveVehicle.getShipperId(), shipper), shipperAddress);
                shipperPhone = nvl(shipper.getPhone(), shipperPhone);
            }
        }

        String consigneeName = nvl(invoiceContext.consignee(), DEFAULT_MALSO_CONSIGNEE_NAME);
        String exportPort = nvl(invoiceContext.exportPort(), DEFAULT_MALSO_EXPORT_PORT);
        String destinationCountry = nvl(invoiceContext.destinationCountry(), DEFAULT_MALSO_DESTINATION_COUNTRY);
        ContainerInfo containerInfo = ContainerInfo.builder()
                .consignee(consigneeName)
                .exportPort(exportPort)
                .destinationCountry(destinationCountry)
                .build();

        Map<Long, BigDecimal> cbmByVehicleId = new HashMap<>();
        Map<Long, BigDecimal> weightByVehicleId = new HashMap<>();
        BigDecimal effectiveWeightKg = resolveMalsoWeightKg(companyId, vehicle, effectiveVehicle);
        BigDecimal effectiveCbm = resolveMalsoCbm(companyId, vehicle, effectiveVehicle);
        weightByVehicleId.put(effectiveVehicle.getId(), effectiveWeightKg != null ? effectiveWeightKg : DEFAULT_MALSO_WEIGHT_KG);
        cbmByVehicleId.put(effectiveVehicle.getId(), effectiveCbm != null ? effectiveCbm : DEFAULT_MALSO_CBM);

        byte[] shipperSignImage = loadShipperSignImage(companyId, effectiveVehicle.getShipperId());

        byte[] baseXlsx = customsInvoiceXlsxGenerator.generate(
                effectiveVehicle,
                crv,
                containerInfo,
                ShippingMethod.RORO,
                new CustomsInvoiceXlsxGenerator.ShipperInfo(shipperName, shipperAddress, shipperPhone),
                shipperSignImage,
                cbmByVehicleId,
                weightByVehicleId,
                nvl(invoiceContext.warehouseLocation(), DEFAULT_MALSO_WAREHOUSE_LOCATION)
        );
        return applyMalsoInvoiceFallbackCells(baseXlsx, DEFAULT_MALSO_CONSIGNEE_ADDRESS, DEFAULT_MALSO_CONSIGNEE_PHONE, DEFAULT_MALSO_RORO_REMARK);
    }

    private String resolveVehicleInvoiceNo(Long companyId, Vehicle vehicle, boolean issueIfMissing) {
        if (!isBlank(vehicle.getExportInvoiceNo())) {
            return vehicle.getExportInvoiceNo().trim();
        }
        if (!issueIfMissing) {
            return null;
        }

        String issued = invoiceNumberService.issueNext(companyId, 0L, InvoiceNumberType.MALSO);
        vehicle.updateExportInvoiceNo(issued);
        vehicleRepo.save(vehicle);
        return issued;
    }

    private MalsoInvoiceContext resolveMalsoInvoiceContext(Long companyId, Vehicle vehicle) {
        TradeCondition tradeCondition = TradeCondition.FOB;
        String warehouseLocation = DEFAULT_MALSO_WAREHOUSE_LOCATION;
        String exportPort = DEFAULT_MALSO_EXPORT_PORT;
        String destinationCountry = DEFAULT_MALSO_DESTINATION_COUNTRY;
        String consignee = DEFAULT_MALSO_CONSIGNEE_NAME;

        if (vehicle.getId() == null) {
            return new MalsoInvoiceContext(tradeCondition, warehouseLocation, exportPort, destinationCountry, consignee);
        }

        try {
            Optional<CustomsRequestVehicle> latestRequestVehicle =
                    customsRequestVehicleRepo.findFirstByVehicleIdOrderByCreatedAtDesc(vehicle.getId());
            if (latestRequestVehicle.isPresent()) {
                CustomsRequestVehicle crv = latestRequestVehicle.get();
                if (crv.getCustomsRequest() != null
                        && companyId.equals(crv.getCustomsRequest().getCompanyId())
                        && crv.getCustomsRequest().getShippingMethod() == ShippingMethod.RORO) {
                    if (crv.getTradeCondition() != null) {
                        tradeCondition = crv.getTradeCondition();
                    }
                    ContainerInfo containerInfo = crv.getCustomsRequest().getContainerInfo();
                    if (containerInfo != null) {
                        warehouseLocation = firstNonBlank(
                                containerInfo.getWarehouseLocation(),
                                containerInfo.getVesselName(),
                                warehouseLocation
                        );
                        exportPort = firstNonBlank(containerInfo.getExportPort(), exportPort);
                        destinationCountry = firstNonBlank(containerInfo.getDestinationCountry(), destinationCountry);
                        consignee = firstNonBlank(containerInfo.getConsignee(), consignee);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[MalsoPrintService] failed to resolve invoice context from customs request. vehicleId={}", vehicle.getId(), e);
        }

        return new MalsoInvoiceContext(tradeCondition, warehouseLocation, exportPort, destinationCountry, consignee);
    }

    private int defaultModelYear() {
        return LocalDate.now().minusYears(5).getYear();
    }

    /**
     * 신고필증 템플릿 구조상 하드코딩된 N/A 셀을 말소 신청용 기본값으로 치환합니다.
     */
    private byte[] applyMalsoInvoiceFallbackCells(byte[] xlsxBytes,
                                                  String consigneeAddress,
                                                  String consigneePhone,
                                                  String roroRemark) {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0);
            setString(sheet, MALSO_INVOICE_TITLE_CELL, MALSO_INVOICE_TITLE);
            setString(sheet, MALSO_CONSIGNEE_ADDRESS_CELL, consigneeAddress);
            setString(sheet, MALSO_CONSIGNEE_PHONE_CELL, consigneePhone);
            setString(sheet, MALSO_RORO_REMARK_CELL, roroRemark);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("[MalsoPrintService] failed to apply invoice fallback cells", e);
            return xlsxBytes;
        }
    }

    private static void setString(Sheet sheet, String addr, String value) {
        CellReference ref = new CellReference(addr);
        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());
        cell.setCellValue(value);
    }

    private long resolveInvoiceAmount(Long amount) {
        if (amount != null && amount > 0) {
            return amount;
        }
        return ThreadLocalRandom.current().nextLong(INVOICE_RANDOM_MIN, INVOICE_RANDOM_MAX + 1);
    }

    private BigDecimal resolveMalsoWeightKg(Long companyId, Vehicle originalVehicle, Vehicle effectiveVehicle) {
        BigDecimal fromEffectiveVehicle = toPositiveDecimal(effectiveVehicle == null ? null : effectiveVehicle.getWeight());
        if (fromEffectiveVehicle != null) {
            return fromEffectiveVehicle;
        }

        BigDecimal fromOriginalVehicle = toPositiveDecimal(originalVehicle == null ? null : originalVehicle.getWeight());
        if (fromOriginalVehicle != null) {
            return fromOriginalVehicle;
        }

        Optional<RegistrationDocument> registrationDocOpt = findLatestRegistrationDocument(companyId, originalVehicle == null ? null : originalVehicle.getId());
        if (registrationDocOpt.isPresent()) {
            BigDecimal fromRegistration = parseNumber(registrationDocOpt.get().getWeight());
            if (fromRegistration != null && fromRegistration.signum() > 0) {
                return fromRegistration;
            }
        }

        return DEFAULT_MALSO_WEIGHT_KG;
    }

    private BigDecimal resolveMalsoCbm(Long companyId, Vehicle originalVehicle, Vehicle effectiveVehicle) {
        BigDecimal fromEffectiveVehicle = calculateCbm(
                toPositiveDecimal(effectiveVehicle == null ? null : effectiveVehicle.getLength()),
                toPositiveDecimal(effectiveVehicle == null ? null : effectiveVehicle.getWidth()),
                toPositiveDecimal(effectiveVehicle == null ? null : effectiveVehicle.getHeight())
        );
        if (fromEffectiveVehicle != null) {
            return fromEffectiveVehicle;
        }

        BigDecimal fromOriginalVehicle = calculateCbm(
                toPositiveDecimal(originalVehicle == null ? null : originalVehicle.getLength()),
                toPositiveDecimal(originalVehicle == null ? null : originalVehicle.getWidth()),
                toPositiveDecimal(originalVehicle == null ? null : originalVehicle.getHeight())
        );
        if (fromOriginalVehicle != null) {
            return fromOriginalVehicle;
        }

        Optional<RegistrationDocument> registrationDocOpt = findLatestRegistrationDocument(companyId, originalVehicle == null ? null : originalVehicle.getId());
        if (registrationDocOpt.isPresent()) {
            RegistrationDocument doc = registrationDocOpt.get();
            BigDecimal fromRegistration = calculateCbm(
                    parseNumber(doc.getLengthVal()),
                    parseNumber(doc.getWidthVal()),
                    parseNumber(doc.getHeightVal())
            );
            if (fromRegistration != null) {
                return fromRegistration;
            }
        }

        return DEFAULT_MALSO_CBM;
    }

    private BigDecimal calculateCbm(BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm) {
        if (lengthMm == null || widthMm == null || heightMm == null) {
            return null;
        }
        if (lengthMm.signum() <= 0 || widthMm.signum() <= 0 || heightMm.signum() <= 0) {
            return null;
        }
        return lengthMm.multiply(widthMm)
                .multiply(heightMm)
                .divide(MM3_PER_M3, 3, RoundingMode.HALF_UP);
    }

    private BigDecimal parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(raw.replace(",", "").trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal toPositiveDecimal(Number value) {
        if (value == null) {
            return null;
        }
        BigDecimal decimal = BigDecimal.valueOf(value.doubleValue());
        return decimal.signum() > 0 ? decimal : null;
    }

    private Set<String> normalizeSelectedKeys(List<String> selectedKeys, boolean includeDeregistration) {
        if (selectedKeys == null || selectedKeys.isEmpty()) {
            LinkedHashSet<String> defaults = new LinkedHashSet<>();
            if (includeDeregistration) {
                defaults.add(KEY_DEREGISTRATION);
            }
            defaults.add(KEY_DEREGISTRATION_APP);
            defaults.add(KEY_OWNER_BIZ_COMBINED);
            defaults.add(KEY_INVOICE);
            defaults.add(KEY_ID_CARD);  // 차주 신분증은 맨 마지막
            return defaults;
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : selectedKeys) {
            if (raw == null || raw.isBlank()) continue;
            String key = raw.trim().toLowerCase(Locale.ROOT);
            if (!VALID_KEYS.contains(key)) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "Unsupported document key: " + raw);
            }
            normalized.add(key);
        }

        if (normalized.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "No documents selected.");
        }
        return normalized;
    }

    private Optional<Document> findLatestOwnerIdCardDocument(Long companyId, Long vehicleId) {
        if (vehicleId == null) {
            return Optional.empty();
        }
        return documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.VEHICLE, vehicleId, DocumentType.ID_CARD
        );
    }

    private Optional<Document> findLatestDeregistrationDocument(Long companyId, Long vehicleId) {
        if (vehicleId == null) {
            return Optional.empty();
        }
        return documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.VEHICLE, vehicleId, DocumentType.DEREGISTRATION
        );
    }

    private Optional<Document> findLatestShipperDocument(Long companyId, Long shipperId, DocumentType docType) {
        if (shipperId == null) {
            return Optional.empty();
        }
        return documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, shipperId, docType
        );
    }

    private String buildOwnerIdCardFilename(String vin, String originalFilename) {
        return FILE_OWNER_ID_PREFIX + vin + ext(originalFilename);
    }

    private String buildDeregistrationFilename(String vin, String originalFilename) {
        return FILE_DEREG_CERT_PREFIX + vin + ext(originalFilename);
    }

    private BufferedImage readFirstPageAsImage(byte[] sourceBytes, Document sourceDoc) {
        if (sourceBytes == null || sourceBytes.length == 0 || sourceDoc == null) {
            return null;
        }
        if (isPdf(sourceDoc)) {
            try (PDDocument pdf = PDDocument.load(sourceBytes)) {
                if (pdf.getNumberOfPages() == 0) {
                    return null;
                }
                return new PDFRenderer(pdf).renderImageWithDPI(0, 200, ImageType.RGB);
            } catch (IOException e) {
                log.warn("[MalsoPrintService] failed to render pdf first page as image", e);
                return null;
            }
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(sourceBytes)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            log.warn("[MalsoPrintService] failed to decode image", e);
            return null;
        }
    }

    private byte[] composeOwnerIdOnBizRegPdf(BufferedImage bizRegImage, BufferedImage ownerIdImage, String vin) {
        boolean rotatedFromLandscape = bizRegImage.getWidth() > bizRegImage.getHeight();
        BufferedImage normalizedBizReg = rotatedFromLandscape ? rotateClockwise90(bizRegImage) : bizRegImage;

        BufferedImage canvas = new BufferedImage(normalizedBizReg.getWidth(), normalizedBizReg.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.drawImage(normalizedBizReg, 0, 0, null);

            int marginX = Math.max(16, Math.round(canvas.getWidth() * 0.03f));
            int marginY = Math.max(16, Math.round(canvas.getHeight() * 0.03f));
            int maxW = Math.max(180, Math.round(canvas.getWidth() * 0.34f));
            int maxH = Math.max(140, Math.round(canvas.getHeight() * (rotatedFromLandscape ? 0.36f : 0.30f)));
            int targetW = maxW;
            int ownerIdWidth = Math.max(1, ownerIdImage.getWidth());
            int ownerIdHeight = Math.max(1, ownerIdImage.getHeight());
            int targetH = Math.max(110, Math.round((float) ownerIdHeight * targetW / ownerIdWidth));
            if (targetH > maxH) {
                targetH = maxH;
                targetW = Math.max(180, Math.round((float) ownerIdWidth * targetH / ownerIdHeight));
            }

            int x = canvas.getWidth() - targetW - marginX;
            int y = Math.max(marginY, (canvas.getHeight() - targetH) / 2);

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRoundRect(x - 8, y - 8, targetW + 16, targetH + 16, 8, 8);
            g.setColor(new Color(180, 180, 180));
            g.drawRoundRect(x - 8, y - 8, targetW + 16, targetH + 16, 8, 8);
            g.drawImage(ownerIdImage, x, y, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        byte[] pngBytes;
        try (ByteArrayOutputStream pngOut = new ByteArrayOutputStream()) {
            ImageIO.write(canvas, "png", pngOut);
            pngBytes = pngOut.toByteArray();
        } catch (IOException e) {
            log.error("[MalsoPrintService] failed to compose owner-id + biz-reg image, vin={}", vin, e);
            throw new IllegalStateException("Failed to compose owner ID + shipper business registration image", e);
        }

        try {
            return toPdf(new DocumentBytes(FILE_OWNER_BIZ_PREFIX + vin + ".png", "image/png", pngBytes));
        } catch (Exception e) {
            log.error("[MalsoPrintService] failed to convert composed image to pdf, vin={}", vin, e);
            throw new IllegalStateException("Failed to compose owner ID + shipper business registration PDF", e);
        }
    }

    private BufferedImage rotateClockwise90(BufferedImage source) {
        BufferedImage rotated = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
            g.translate(rotated.getWidth(), 0);
            g.rotate(Math.toRadians(90));
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }

    /**
     * 화주의 사인방(SIGN) 이미지를 S3에서 읽어옵니다.
     */
    private byte[] loadShipperSignImage(Long companyId, Long shipperId) {
        if (shipperId == null) {
            return null;
        }

        Document signDoc = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.SIGN
        ).orElse(null);
        if (signDoc == null || isBlank(signDoc.getS3Key())) {
            return null;
        }

        byte[] raw = s3Reader.readBytes(signDoc.getS3Key());
        if (raw == null || raw.length == 0) {
            return null;
        }
        return toPng(raw, signDoc);
    }

    private byte[] toPng(byte[] raw, Document doc) {
        if (isPdf(doc)) {
            return pdfFirstPageToPng(raw);
        }
        return imageToPng(raw);
    }

    private boolean isPdf(Document doc) {
        String contentType = doc.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf")) {
            return true;
        }
        String filename = doc.getOriginalFilename();
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private byte[] pdfFirstPageToPng(byte[] pdfBytes) {
        try (PDDocument pdf = PDDocument.load(pdfBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (pdf.getNumberOfPages() == 0) {
                return null;
            }
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 200);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("[MalsoPrintService] failed to render sign pdf to png", e);
            return null;
        }
    }

    private byte[] imageToPng(byte[] imageBytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(imageBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                return null;
            }
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("[MalsoPrintService] failed to convert sign image to png", e);
            return null;
        }
    }

    private Vehicle loadVehicle(Long companyId, Long vehicleId) {
        return vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }

    private PrintContext loadPrintContext(Long companyId, Long vehicleId) {
        Vehicle vehicle = loadVehicle(companyId, vehicleId);
        String vin = safeVin(vehicle);

        List<String> missingData = new ArrayList<>();
        List<String> missingDocuments = new ArrayList<>();
        if (vehicle.getOwnerType() == null) missingData.add("Owner type (ownerType)");
        if (isBlank(vehicle.getVehicleNo())) missingData.add("Vehicle number (vehicleNo)");
        if (isBlank(vehicle.getVin())) missingData.add("VIN (vin)");
        if (isBlank(vehicle.getModelName())) missingData.add("Model name (modelName)");
        if (isBlank(vehicle.getOwnerName())) missingData.add("Owner name (ownerName)");
        if (isBlank(vehicle.getOwnerId())) missingData.add("Owner identifier (ownerId)");

        if (vehicle.getShipperId() == null) {
            missingData.add("Shipper information (shipperId)");
        }

        Shipper shipper = null;
        if (vehicle.getShipperId() != null) {
            shipper = shipperRepo.findByIdAndCompanyId(vehicle.getShipperId(), companyId).orElse(null);
            if (shipper == null) {
                missingData.add("Shipper master record (shipper)");
            } else {
                if (isBlank(shipper.getName())) missingData.add("Shipper name (shipper.name)");
                if (isBlank(resolveShipperBusinessNumber(companyId, vehicle.getShipperId(), shipper))) {
                    missingData.add("Shipper business number (shipper.businessNumber or biz_reg.bizNumber)");
                }
                if (isBlank(resolveShipperAddress(companyId, vehicle.getShipperId(), shipper))) {
                    missingData.add("Shipper address (shipper.address or biz_reg.bizAddress)");
                }
            }
        }

        Optional<Document> deregistrationDoc = findLatestDeregistrationDocument(companyId, vehicle.getId());
        if (deregistrationDoc.isPresent() && isBlank(deregistrationDoc.get().getS3Key())) {
            missingDocuments.add("Deregistration file key (s3Key)");
        }

        // 합본 문서가 있으면 대표자신분증+사업자등록증 각각 체크 불필요
        Optional<Document> combinedDoc = (vehicle.getShipperId() != null)
                ? findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.BIZ_ID_COMBINED)
                : Optional.empty();
        boolean hasCombined = combinedDoc.isPresent() && !isBlank(combinedDoc.get().getS3Key());

        // 차주 신분증 (선택사항 — missing에 추가하지 않음)
        Optional<Document> ownerIdCardDoc = findLatestOwnerIdCardDocument(companyId, vehicle.getId());

        // 대표자 신분증 (화주) — 합본용
        Optional<Document> shipperIdCardDoc = Optional.empty();
        Optional<Document> shipperBizRegDoc = Optional.empty();

        if (hasCombined) {
            // 합본 문서가 있으면 대표자신분증과 사업자등록증 모두 합본 문서로 대체
            shipperIdCardDoc = combinedDoc;
            shipperBizRegDoc = combinedDoc;
        } else if (vehicle.getShipperId() != null) {
            shipperIdCardDoc = findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.ID_CARD);
            if (shipperIdCardDoc.isEmpty()) {
                missingDocuments.add("Shipper representative ID card (대표자 신분증)");
            } else if (isBlank(shipperIdCardDoc.get().getS3Key())) {
                missingDocuments.add("Shipper representative ID card file key (s3Key)");
            }

            shipperBizRegDoc = findLatestShipperDocument(companyId, vehicle.getShipperId(), DocumentType.BIZ_REGISTRATION);
            if (shipperBizRegDoc.isEmpty()) {
                missingDocuments.add("Shipper business registration (BIZ_REGISTRATION)");
            } else if (isBlank(shipperBizRegDoc.get().getS3Key())) {
                missingDocuments.add("Shipper business registration file key (s3Key)");
            }

            if (shipperIdCardDoc.isEmpty() || shipperBizRegDoc.isEmpty()) {
                missingDocuments.add("Input documents required for representative ID + shipper business registration generation");
            }
        }

        return new PrintContext(
                vehicle,
                vin,
                shipper,
                deregistrationDoc,
                ownerIdCardDoc,
                shipperIdCardDoc,
                shipperBizRegDoc,
                List.copyOf(missingData),
                List.copyOf(missingDocuments)
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * BIZ_REGISTRATION 우선, 없으면 BIZ_ID_COMBINED 합본 문서를 찾는다.
     */
    private Optional<Document> findShipperBizRegOrCombinedDoc(Long companyId, Long shipperId) {
        Optional<Document> doc = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.BIZ_REGISTRATION
        );
        if (doc.isEmpty()) {
            doc = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                    companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.BIZ_ID_COMBINED
            );
        }
        return doc;
    }

    private String resolveShipperAddress(Long companyId, Long shipperId, Shipper shipper) {
        if (shipperId == null) {
            return shipper != null ? shipper.getAddress() : null;
        }

        // 1) BizRegDocument 엔티티 필드 (BIZ_REGISTRATION 또는 BIZ_ID_COMBINED)
        String fromEntity = findShipperBizRegOrCombinedDoc(companyId, shipperId)
                .map(this::extractBizRegAddress)
                .filter(value -> !isBlank(value))
                .orElse(null);
        if (!isBlank(fromEntity)) return fromEntity;

        // 2) OCR job result JSON
        String fromJob = resolveBizRegFieldFromJob(companyId, shipperId, "bizAddress");
        if (!isBlank(fromJob)) return fromJob;

        // 3) 화주 마스터 주소
        return shipper != null ? shipper.getAddress() : null;
    }

    private String resolveShipperCompanyName(Long companyId, Long shipperId, Shipper shipper) {
        if (shipperId == null) {
            return shipper != null ? nvl(shipper.getName(), null) : null;
        }

        // 1) BizRegDocument 엔티티 필드 (BIZ_REGISTRATION 또는 BIZ_ID_COMBINED)
        String fromEntity = findShipperBizRegOrCombinedDoc(companyId, shipperId)
                .map(this::extractBizRegCompanyName)
                .filter(value -> !isBlank(value))
                .orElse(null);
        if (!isBlank(fromEntity)) return fromEntity;

        // 2) OCR job result JSON 에서 확인
        String fromJob = resolveBizRegFieldFromJob(companyId, shipperId, "companyName");
        if (!isBlank(fromJob)) return fromJob;

        // 3) 화주 마스터 이름
        return shipper != null ? nvl(shipper.getName(), null) : null;
    }

    private String resolveShipperRepresentativeName(Long companyId, Long shipperId, Shipper shipper) {
        if (shipperId == null) {
            return shipper != null ? nvl(shipper.getName(), null) : null;
        }

        // 1) BizRegDocument 엔티티 필드 (BIZ_REGISTRATION 또는 BIZ_ID_COMBINED)
        String fromEntity = findShipperBizRegOrCombinedDoc(companyId, shipperId)
                .map(this::extractBizRegRepresentativeName)
                .filter(value -> !isBlank(value))
                .orElse(null);
        if (!isBlank(fromEntity)) return sanitizeRepresentativeName(fromEntity);

        // 2) OCR job result JSON
        String fromJob = resolveBizRegFieldFromJob(companyId, shipperId, "representativeName");
        if (!isBlank(fromJob)) return sanitizeRepresentativeName(fromJob);

        // 3) 화주 마스터 이름
        return shipper != null ? nvl(shipper.getName(), null) : null;
    }

    private String resolveShipperBusinessNumber(Long companyId, Long shipperId, Shipper shipper) {
        String masterBizNo = normalizeBizNumber(shipper != null ? shipper.getBusinessNumber() : null);
        if (!isBlank(masterBizNo)) {
            return masterBizNo;
        }
        if (shipperId == null) {
            return null;
        }

        // 1) BizRegDocument 엔티티 필드
        String fromEntity = findShipperBizRegOrCombinedDoc(companyId, shipperId)
                .map(this::extractBizRegNumber)
                .map(this::normalizeBizNumber)
                .orElse(null);
        if (!isBlank(fromEntity)) return fromEntity;

        // 2) OCR job result JSON
        String fromJob = resolveBizRegFieldFromJob(companyId, shipperId, "bizNumber");
        return normalizeBizNumber(fromJob);
    }

    /**
     * OCR job result JSON에서 사업자등록증 필드 추출.
     * BizRegDocument 엔티티 필드가 비어있을 때 폴백으로 사용.
     */
    private String resolveBizRegFieldFromJob(Long companyId, Long shipperId, String fieldName) {
        // BizReg document ID 찾기 (BIZ_REGISTRATION 우선, 없으면 BIZ_ID_COMBINED 합본)
        Optional<Document> bizRegDoc = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.BIZ_REGISTRATION
        );
        if (bizRegDoc.isEmpty()) {
            bizRegDoc = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                    companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.BIZ_ID_COMBINED
            );
        }
        if (bizRegDoc.isEmpty()) return null;

        return jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                companyId, bizRegDoc.get().getId(), OcrJobStatus.SUCCEEDED
        ).map(job -> {
            try {
                String resultJson = job.getResultJson();
                if (isBlank(resultJson)) return null;
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resultJson);
                // ParsedBizReg 구조: 직접 필드 또는 parsed 하위에 있을 수 있음
                String value = jsonText(root, fieldName);
                if (isBlank(value) && root.has("parsed")) {
                    value = jsonText(root.get("parsed"), fieldName);
                }
                return value;
            } catch (Exception e) {
                log.warn("[MalsoPrintService] BizReg OCR job 결과 파싱 실패: {}", e.getMessage());
                return null;
            }
        }).orElse(null);
    }

    /**
     * 대표자 성명에서 생년월일 등 불필요한 정보를 제거.
     * 예: "박영화 생 년 월 일 : 1967 년 03 월 27 일" → "박영화"
     */
    private String sanitizeRepresentativeName(String raw) {
        if (isBlank(raw)) return null;
        // "생" 또는 "생년월일" 이후 전부 제거
        String cleaned = raw.replaceAll("\\s*생\\s*년?\\s*월?\\s*일?\\s*[:：]?\\s*.*", "").trim();
        // 날짜 패턴 제거 (YYYY년 MM월 DD일, YYYY-MM-DD 등)
        cleaned = cleaned.replaceAll("\\s*\\d{4}\\s*년?.*", "").trim();
        // 괄호 내용 제거 (생년월일 등)
        cleaned = cleaned.replaceAll("\\s*\\(.*?\\)\\s*", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String extractBizRegCompanyName(Document doc) {
        if (doc instanceof BizRegDocument bizReg) {
            return nvl(bizReg.getCompanyName(), null);
        }
        return null;
    }

    private String extractBizRegAddress(Document doc) {
        if (doc instanceof BizRegDocument bizReg) {
            return nvl(bizReg.getBizAddress(), null);
        }
        return null;
    }

    private String extractBizRegNumber(Document doc) {
        if (doc instanceof BizRegDocument bizReg) {
            return nvl(bizReg.getBizNumber(), null);
        }
        return null;
    }

    private String extractBizRegRepresentativeName(Document doc) {
        if (doc instanceof BizRegDocument bizReg) {
            return nvl(bizReg.getRepresentativeName(), null);
        }
        return null;
    }

    private String normalizeBizNumber(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 10) {
            return null;
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }

    private String cacheKey(Long companyId, Long vehicleId, String docType, String vin) {
        return CACHE_PREFIX + companyId + "/" + vehicleId + "/" + docType + "_" + vin + "_" + CACHE_VERSION + ".pdf";
    }

    private String deregistrationAppCacheKey(Long companyId,
                                             Vehicle vehicle,
                                             String vin,
                                             Document ownerIdCardDoc,
                                             Document shipperBizRegDoc,
                                             RegistrationDocument registrationDoc) {
        String sourceStamp = cacheStamp(
                DEREG_APP_TEMPLATE_VERSION,
                cachePart(ownerIdCardDoc),
                cachePart(shipperBizRegDoc),
                cachePart(registrationDoc),
                vehicle == null ? null : vehicle.getOwnerName(),
                vehicle == null ? null : vehicle.getOwnerId(),
                vehicle == null ? null : vehicle.getShipperName(),
                vehicle == null ? null : vehicle.getVehicleNo(),
                vehicle == null ? null : vehicle.getVin(),
                vehicle == null || vehicle.getMileageKm() == null ? null : String.valueOf(vehicle.getMileageKm())
        );
        return cacheKey(companyId, vehicle.getId(), "deregistration_app_" + sourceStamp, vin);
    }

    private String ownerBizCombinedCacheKey(Long companyId,
                                            Long vehicleId,
                                            String vin,
                                            Document ownerIdCardDoc,
                                            Document shipperBizRegDoc) {
        String sourceStamp = cacheStamp(
                cachePart(ownerIdCardDoc),
                cachePart(shipperBizRegDoc)
        );
        return cacheKey(companyId, vehicleId, "owner_biz_combined_" + sourceStamp, vin);
    }

    private String mergedPdfCacheKey(Long companyId,
                                     Vehicle vehicle,
                                     String vin,
                                     Set<String> keys,
                                     Document deregistrationDoc,
                                     Document ownerIdCardDoc,
                                     Document shipperIdCardDoc,
                                     Document shipperBizRegDoc) {
        RegistrationDocument registrationDoc = findLatestRegistrationDocument(companyId, vehicle.getId()).orElse(null);
        String sourceStamp = cacheStamp(
                String.join("-", keys),
                cachePart(vehicle == null ? null : vehicle.getUpdatedAt()),
                cachePart(deregistrationDoc),
                cachePart(ownerIdCardDoc),
                cachePart(shipperIdCardDoc),
                cachePart(shipperBizRegDoc),
                cachePart(registrationDoc),
                keys.contains(KEY_DEREGISTRATION_APP)
                        ? deregistrationAppCacheKey(companyId, vehicle, vin, ownerIdCardDoc, shipperBizRegDoc, registrationDoc)
                        : null,
                keys.contains(KEY_OWNER_BIZ_COMBINED)
                        ? ownerBizCombinedCacheKey(companyId, vehicle.getId(), vin, shipperIdCardDoc, shipperBizRegDoc)
                        : null,
                keys.contains(KEY_INVOICE)
                        ? cacheKey(companyId, vehicle.getId(), INVOICE_CACHE_DOC_TYPE, vin)
                        : null
        );
        return cacheKey(companyId, vehicle.getId(), "merged_" + sourceStamp, vin);
    }

    private String cachePart(Document doc) {
        if (doc == null) {
            return null;
        }
        return cacheStamp(
                doc.getId() == null ? null : String.valueOf(doc.getId()),
                doc.getS3Key(),
                cachePart(doc.getUploadedAt()),
                cachePart(doc.getUpdatedAt())
        );
    }

    private String cachePart(Instant value) {
        return value == null ? null : String.valueOf(value.toEpochMilli());
    }

    private String cacheStamp(String... parts) {
        int hash = 17;
        if (parts != null) {
            for (String part : parts) {
                hash = 31 * hash + (part == null ? 0 : part.hashCode());
            }
        }
        return Integer.toUnsignedString(hash, 36);
    }

    private void cacheToS3(String key, byte[] data, String contentType) {
        try {
            s3Upload.upload(data, key, contentType);
            log.debug("[MalsoPrintService] cached to S3 key={}", key);
        } catch (Exception e) {
            log.warn("[MalsoPrintService] cache upload failed key={}", key, e);
            // 캐시 실패는 무시 (다음에 다시 생성)
        }
    }

    private static String safeVin(Vehicle v) {
        String vin = v.getVin();
        if (vin == null || vin.isBlank()) return "NO_VIN";
        return vin.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static String nvl(String a, String fallback) {
        return (a != null && !a.isBlank()) ? a : fallback;
    }

    private static String ext(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    // ================== DTOs ==================

    public record PrintItem(
            Long documentId,
            String documentName,
            String documentType,
            String key,
            String s3Key,
            String s3Url,
            String filename,
            Long sizeBytes,
            Instant date,
            String contentType,
            boolean available
    ) {}

    public record PrintItemsResponse(
            Long vehicleId,
            String vin,
            String ownerType,
            List<PrintItem> items,
            List<String> missingData,
            List<String> missingDocuments,
            Long invoiceAmount,
            boolean invoicePrepared
    ) {}

    public record InvoicePrepareResponse(
            Long vehicleId,
            Long amount,
            boolean autoFilled,
            String message,
            PrintItemsResponse items
    ) {}

    public record DocumentBytes(
            String filename,
            String contentType,
            byte[] data
    ) {}

    private record PrintContext(
            Vehicle vehicle,
            String vin,
            Shipper shipper,
            Optional<Document> deregistrationDoc,
            Optional<Document> ownerIdCardDoc,       // 차주 신분증 (선택사항)
            Optional<Document> shipperIdCardDoc,      // 대표자 신분증 (화주)
            Optional<Document> shipperBizRegDoc,      // 사업자등록증 (화주)
            List<String> missingData,
            List<String> missingDocuments
    ) {}

    private record OwnerInfo(
            String name,
            String idNo,
            String address
    ) {}

    private record MalsoInvoiceContext(
            TradeCondition tradeCondition,
            String warehouseLocation,
            String exportPort,
            String destinationCountry,
            String consignee
    ) {}
}
