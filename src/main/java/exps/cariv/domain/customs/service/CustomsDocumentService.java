package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.entity.CustomsRequest;
import exps.cariv.domain.customs.entity.CustomsRequestVehicle;
import exps.cariv.domain.customs.entity.ContainerInfo;
import exps.cariv.domain.customs.entity.InvoiceNumberType;
import exps.cariv.domain.customs.entity.ShippingMethod;
import exps.cariv.domain.customs.repository.CustomsRequestRepository;
import exps.cariv.domain.customs.repository.CustomsRequestVehicleRepository;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.export.entity.Export;
import exps.cariv.domain.export.repository.ExportRepository;
import exps.cariv.domain.malso.print.XlsxToPdfConverter;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.domain.shipper.entity.BizRegDocument;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.entity.ShipperType;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.aws.S3ObjectReader;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 관세사 전송 시 문서 패키징 서비스.
 *
 * <p>전송 버튼 클릭 시 아래 문서를 PDF 로 생성/취합하여 프론트에 반환:
 * <ol>
 *   <li>Invoice / Packing List (금액, 거래조건, 차량정보, 컨테이너정보 포함)</li>
 *   <li>말소증 (차량의 DEREGISTRATION 문서)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomsDocumentService {

    private final CustomsRequestRepository requestRepo;
    private final CustomsRequestVehicleRepository requestVehicleRepo;
    private final VehicleRepository vehicleRepo;
    private final DocumentRepository documentRepo;
    private final RegistrationDocumentRepository registrationDocRepo;
    private final ExportRepository exportRepo;
    private final ShipperRepository shipperRepo;
    private final CustomsInvoiceXlsxGenerator invoiceGenerator;
    private final InvoiceNumberService invoiceNumberService;
    private final XlsxToPdfConverter pdfConverter;
    private final S3ObjectReader s3Reader;
    private final S3Upload s3Upload;

    private static final BigDecimal MM3_PER_M3 = new BigDecimal("1000000000");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");
    private static final String GENERATED_DOCS_PREFIX = "generated-docs/customs/";
    private static final String GENERATED_DOCS_CACHE_VERSION = "v1";

    private record VehicleMetricMaps(
            Map<Long, BigDecimal> cbmByVehicleId,
            Map<Long, BigDecimal> weightByVehicleId
    ) {
    }

    private record ShipperInvoiceData(
            CustomsInvoiceXlsxGenerator.ShipperInfo shipperInfo,
            byte[] signaturePng
    ) {
    }

    private record InvoiceSourceContext(
            List<Vehicle> vehicles,
            ContainerInfo containerInfo,
            String warehouseLocation
    ) {
    }

    /**
     * 전송 요청에 대한 전체 문서 패키지를 생성한다.
     *
     * @return 문서명 → PDF 바이트 맵
     */
    @Transactional
    public Map<String, byte[]> buildDocumentPackage(Long companyId, Long requestId) {
        CustomsRequest cr = requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        List<CustomsRequestVehicle> crvs = requestVehicleRepo.findAllByCustomsRequestId(requestId);
        if (crvs.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }

        // 차량 로드
        List<Vehicle> vehicles = new ArrayList<>();
        for (CustomsRequestVehicle crv : crvs) {
            Vehicle v = vehicleRepo.findActiveByIdAndCompanyId(crv.getVehicleId(), companyId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
            vehicles.add(v);
        }

        Map<String, byte[]> docs = new LinkedHashMap<>();

        // 1. Invoice / Packing List PDF
        byte[] invoicePdf = buildInvoicePdf(companyId, vehicles, crvs, cr);
        docs.put("invoice.pdf", invoicePdf);

        // 2. 각 차량의 말소증
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            byte[] deregPdf = loadDeregistrationPdf(companyId, v);
            if (deregPdf != null) {
                String name = vehicles.size() == 1
                        ? "deregistration.pdf"
                        : "deregistration_" + safe(v.getVin()) + ".pdf";
                docs.put(name, deregPdf);
            }
        }

        return docs;
    }

    /**
     * 전체 문서를 하나의 PDF 로 병합.
     * Map 중간 단계를 건너뛰고 직접 PDF 리스트를 구성하여 메모리 중복을 줄입니다.
     */
    @Transactional
    public byte[] buildMergedPdf(Long companyId, Long requestId) {
        CustomsRequest cr = requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        List<CustomsRequestVehicle> crvs = requestVehicleRepo.findAllByCustomsRequestId(requestId);
        if (crvs.isEmpty()) throw new CustomException(ErrorCode.NOT_FOUND);

        List<Vehicle> vehicles = new ArrayList<>();
        for (CustomsRequestVehicle crv : crvs) {
            vehicles.add(vehicleRepo.findActiveByIdAndCompanyId(crv.getVehicleId(), companyId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND)));
        }

        List<byte[]> pdfList = new ArrayList<>();
        pdfList.add(buildInvoicePdf(companyId, vehicles, crvs, cr));

        for (Vehicle v : vehicles) {
            byte[] dereg = loadDeregistrationPdf(companyId, v);
            if (dereg != null) pdfList.add(dereg);
        }

        return mergePdfs(pdfList);
    }

    /**
     * 생성 문서 목록 응답에서 사용하기 위한 원본 S3 key 맵.
     * <p>invoice.pdf 는 생성형 문서이므로 source key 가 없다(null).</p>
     */
    @Transactional(readOnly = true)
    public Map<String, String> buildDocumentSourceS3KeyMap(Long companyId, Long requestId) {
        requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        List<CustomsRequestVehicle> crvs = requestVehicleRepo.findAllByCustomsRequestId(requestId);
        if (crvs.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }

        List<Vehicle> vehicles = new ArrayList<>();
        for (CustomsRequestVehicle crv : crvs) {
            vehicles.add(vehicleRepo.findActiveByIdAndCompanyId(crv.getVehicleId(), companyId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND)));
        }

        Map<String, String> sourceS3KeyMap = new LinkedHashMap<>();
        sourceS3KeyMap.put("invoice.pdf", null);

        for (Vehicle vehicle : vehicles) {
            findLatestDeregistrationDocument(companyId, vehicle)
                    .ifPresent(doc -> {
                        String name = vehicles.size() == 1
                                ? "deregistration.pdf"
                                : "deregistration_" + safe(vehicle.getVin()) + ".pdf";
                        sourceS3KeyMap.put(name, doc.getS3Key());
                    });
        }

        return sourceS3KeyMap;
    }

    /**
     * generated PDF를 public S3에 캐시하고 key를 반환한다.
     */
    public String ensureGeneratedDocOnS3(Long companyId, Long requestId, String filename, byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        String key = buildGeneratedDocKey(companyId, requestId, filename);
        if (s3Reader.readMeta(key) == null) {
            s3Upload.upload(pdfBytes, key, "application/pdf");
        }
        return key;
    }

    public String toPublicS3Url(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return null;
        }
        return s3Upload.toUrl(s3Key);
    }

    // ─── Invoice 생성 ───

    private byte[] buildInvoicePdf(Long companyId,
                                   List<Vehicle> vehicles,
                                   List<CustomsRequestVehicle> crvs,
                                   CustomsRequest cr) {
        String fixedInvoiceNo = ensureFixedInvoiceNo(companyId, cr, vehicles);
        log.debug("[CustomsDocumentService] using fixed invoiceNo={} requestId={}", fixedInvoiceNo, cr.getId());

        byte[] xlsxBytes;
        VehicleMetricMaps metricMaps = buildVehicleMetricMaps(companyId, vehicles);
        ShipperInvoiceData shipperData = resolveShipperInvoiceData(companyId, vehicles);
        InvoiceSourceContext sourceContext = buildInvoiceSourceContext(
                companyId,
                vehicles,
                cr.getContainerInfo(),
                cr.getShippingMethod()
        );

        if (sourceContext.vehicles().size() == 1) {
            xlsxBytes = invoiceGenerator.generate(
                    sourceContext.vehicles().get(0),
                    crvs.get(0),
                    sourceContext.containerInfo(),
                    cr.getShippingMethod(),
                    shipperData.shipperInfo(),
                    shipperData.signaturePng(),
                    metricMaps.cbmByVehicleId(),
                    metricMaps.weightByVehicleId(),
                    sourceContext.warehouseLocation()
            );
        } else {
            xlsxBytes = invoiceGenerator.generateMulti(
                    sourceContext.vehicles(),
                    crvs,
                    sourceContext.containerInfo(),
                    cr.getShippingMethod(),
                    shipperData.shipperInfo(),
                    shipperData.signaturePng(),
                    metricMaps.cbmByVehicleId(),
                    metricMaps.weightByVehicleId(),
                    sourceContext.warehouseLocation()
            );
        }

        return pdfConverter.convert(xlsxBytes);
    }

    private String ensureFixedInvoiceNo(Long companyId, CustomsRequest request, List<Vehicle> vehicles) {
        if (!isBlank(request.getInvoiceNo())) {
            syncVehicleInvoiceNo(vehicles, request.getInvoiceNo());
            return request.getInvoiceNo();
        }

        String reusableInvoiceNo = resolveReusableVehicleInvoiceNo(vehicles);
        String invoiceNo = reusableInvoiceNo != null
                ? reusableInvoiceNo
                : invoiceNumberService.issueNext(
                        companyId,
                        request.getCustomsBrokerId(),
                        InvoiceNumberType.fromShippingMethod(request.getShippingMethod())
                );

        request.assignInvoiceNoIfAbsent(invoiceNo);
        requestRepo.save(request);
        syncVehicleInvoiceNo(vehicles, invoiceNo);
        return invoiceNo;
    }

    private void syncVehicleInvoiceNo(List<Vehicle> vehicles, String invoiceNo) {
        if (isBlank(invoiceNo) || vehicles == null || vehicles.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (Vehicle vehicle : vehicles) {
            if (vehicle == null) {
                continue;
            }
            if (!invoiceNo.equals(vehicle.getExportInvoiceNo())) {
                vehicle.updateExportInvoiceNo(invoiceNo);
                changed = true;
            }
        }
        if (changed) {
            vehicleRepo.saveAll(vehicles);
        }
    }

    private String resolveReusableVehicleInvoiceNo(List<Vehicle> vehicles) {
        if (vehicles == null || vehicles.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (Vehicle vehicle : vehicles) {
            if (vehicle == null || isBlank(vehicle.getExportInvoiceNo())) {
                continue;
            }
            candidates.add(vehicle.getExportInvoiceNo().trim());
        }
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        return null;
    }

    private InvoiceSourceContext buildInvoiceSourceContext(Long companyId,
                                                           List<Vehicle> vehicles,
                                                           ContainerInfo containerInfo,
                                                           ShippingMethod shippingMethod) {
        if (vehicles.isEmpty()) {
            return new InvoiceSourceContext(List.of(), containerInfo, null);
        }

        List<Vehicle> enrichedVehicles = new ArrayList<>(vehicles.size());
        String fallbackConsigneeName = null;
        String fallbackLoadingPort = null;
        String fallbackDestinationCountry = null;

        for (Vehicle vehicle : vehicles) {
            RegistrationDocument regDoc = registrationDocRepo
                    .findByCompanyIdAndRefTypeAndRefIdAndType(
                            companyId, DocumentRefType.VEHICLE, vehicle.getId(), DocumentType.REGISTRATION)
                    .orElse(null);
            Export exportDoc = exportRepo.findFirstByCompanyIdAndVehicleIdOrderByCreatedAtDesc(companyId, vehicle.getId())
                    .orElse(null);

            enrichedVehicles.add(enrichVehicleForInvoice(vehicle, regDoc, exportDoc));

            if (exportDoc != null) {
                fallbackConsigneeName = firstNonBlank(fallbackConsigneeName, exportDoc.getBuyerName());
                String loadingPort = firstNonBlank(exportDoc.getLoadingPortName(), exportDoc.getLoadingPortCode());
                fallbackLoadingPort = firstNonBlank(fallbackLoadingPort, loadingPort);
                fallbackDestinationCountry = firstNonBlank(
                        fallbackDestinationCountry,
                        exportDoc.getDestCountryName(),
                        exportDoc.getDestCountryCode()
                );
            }
        }

        ContainerInfo invoiceContainerInfo = adaptContainerInfoForInvoice(
                containerInfo,
                fallbackConsigneeName,
                fallbackLoadingPort,
                fallbackDestinationCountry
        );
        String warehouseLocation = null;
        if (shippingMethod == ShippingMethod.RORO) {
            warehouseLocation = firstNonBlank(
                    invoiceContainerInfo == null ? null : invoiceContainerInfo.getWarehouseLocation(),
                    fallbackLoadingPort
            );
        }

        return new InvoiceSourceContext(enrichedVehicles, invoiceContainerInfo, warehouseLocation);
    }

    private Vehicle enrichVehicleForInvoice(Vehicle vehicle,
                                            RegistrationDocument regDoc,
                                            Export exportDoc) {
        String modelName = firstNonBlank(
                vehicle.getModelName(),
                regDoc == null ? null : regDoc.getModelName(),
                exportDoc == null ? null : exportDoc.getItemName()
        );
        String vin = firstNonBlank(
                vehicle.getVin(),
                regDoc == null ? null : regDoc.getVin(),
                exportDoc == null ? null : exportDoc.getChassisNo()
        );
        Integer modelYear = firstNonNull(
                vehicle.getModelYear(),
                regDoc == null ? null : regDoc.getModelYear(),
                parseInteger(exportDoc == null ? null : exportDoc.getModelYear())
        );
        Integer displacement = firstNonNull(
                vehicle.getDisplacement(),
                regDoc == null ? null : regDoc.getDisplacement()
        );
        Long purchasePrice = firstNonNull(
                vehicle.getPurchasePrice(),
                exportDoc == null ? null : exportDoc.getAmountKrw()
        );

        return Vehicle.builder()
                .id(vehicle.getId())
                .vin(vin)
                .modelName(modelName)
                .modelYear(modelYear)
                .fuelType(vehicle.getFuelType())
                .displacement(displacement)
                .shipperId(vehicle.getShipperId())
                .shipperName(vehicle.getShipperName())
                .ownerType(vehicle.getOwnerType())
                .purchasePrice(purchasePrice)
                .exportInvoiceNo(vehicle.getExportInvoiceNo())
                .build();
    }

    private ContainerInfo adaptContainerInfoForInvoice(ContainerInfo base,
                                                       String fallbackConsigneeName,
                                                       String fallbackExportPort,
                                                       String fallbackDestinationCountry) {
        return ContainerInfo.builder()
                .containerNo(base == null ? null : base.getContainerNo())
                .sealNo(base == null ? null : base.getSealNo())
                .entryPort(base == null ? null : base.getEntryPort())
                .warehouseLocation(base == null ? null : base.getWarehouseLocation())
                .vesselName(base == null ? null : base.getVesselName())
                .exportPort(firstNonBlank(base == null ? null : base.getExportPort(), fallbackExportPort))
                .destinationCountry(firstNonBlank(base == null ? null : base.getDestinationCountry(), fallbackDestinationCountry))
                .consignee(firstNonBlank(base == null ? null : base.getConsignee(), fallbackConsigneeName))
                .build();
    }

    private VehicleMetricMaps buildVehicleMetricMaps(Long companyId, List<Vehicle> vehicles) {
        Map<Long, BigDecimal> cbmByVehicleId = new HashMap<>();
        Map<Long, BigDecimal> weightByVehicleId = new HashMap<>();

        if (vehicles.isEmpty()) {
            return new VehicleMetricMaps(cbmByVehicleId, weightByVehicleId);
        }

        for (Vehicle vehicle : vehicles) {
            RegistrationDocument doc = registrationDocRepo
                    .findByCompanyIdAndRefTypeAndRefIdAndType(
                            companyId, DocumentRefType.VEHICLE, vehicle.getId(), DocumentType.REGISTRATION)
                    .orElse(null);
            if (doc == null) continue;

            BigDecimal cbm = calculateCbm(doc);
            if (cbm != null) {
                cbmByVehicleId.put(vehicle.getId(), cbm);
            }

            BigDecimal weight = parseNumber(doc.getWeight());
            if (weight != null && weight.signum() > 0) {
                weightByVehicleId.put(vehicle.getId(), weight);
            }
        }

        return new VehicleMetricMaps(cbmByVehicleId, weightByVehicleId);
    }

    private BigDecimal calculateCbm(RegistrationDocument doc) {
        BigDecimal lengthMm = parseNumber(doc.getLengthVal());
        BigDecimal widthMm = parseNumber(doc.getWidthVal());
        BigDecimal heightMm = parseNumber(doc.getHeightVal());
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
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().replace(",", "");
        Matcher matcher = NUMBER_PATTERN.matcher(normalized);
        if (!matcher.find()) return null;

        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ShipperInvoiceData resolveShipperInvoiceData(Long companyId, List<Vehicle> vehicles) {
        if (vehicles.isEmpty()) {
            return new ShipperInvoiceData(
                    new CustomsInvoiceXlsxGenerator.ShipperInfo("", "", ""),
                    null
            );
        }

        Vehicle firstVehicle = vehicles.get(0);
        Long shipperId = firstVehicle.getShipperId();
        Shipper shipper = null;
        if (shipperId != null) {
            shipper = shipperRepo.findByIdAndCompanyId(shipperId, companyId).orElse(null);
        }

        String shipperName = shipper != null
                ? shipper.getName()
                : firstNonBlank(firstVehicle.getShipperName(), "");
        String shipperAddress = resolveShipperAddress(companyId, shipperId, shipper);
        String shipperPhone = shipper != null ? shipper.getPhone() : "";

        byte[] signaturePng = loadShipperSignaturePng(companyId, shipperId);

        return new ShipperInvoiceData(
                new CustomsInvoiceXlsxGenerator.ShipperInfo(
                        firstNonBlank(shipperName, ""),
                        firstNonBlank(shipperAddress, ""),
                        firstNonBlank(shipperPhone, "")
                ),
                signaturePng
        );
    }

    private String resolveShipperAddress(Long companyId, Long shipperId, Shipper shipper) {
        String masterAddress = shipper != null ? shipper.getAddress() : null;
        if (shipperId == null) {
            return firstNonBlank(masterAddress, "");
        }

        String bizRegAddress = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                        companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.BIZ_REGISTRATION
                )
                .map(this::extractBizRegAddress)
                .orElse(null);

        return firstNonBlank(masterAddress, bizRegAddress, "");
    }

    private String extractBizRegAddress(Document doc) {
        if (doc instanceof BizRegDocument bizReg) {
            return firstNonBlank(bizReg.getBizAddress());
        }
        return null;
    }

    private byte[] loadShipperSignaturePng(Long companyId, Long shipperId) {
        if (shipperId == null) {
            return null;
        }

        Document signDoc = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, shipperId, DocumentType.SIGN
        ).orElse(null);
        if (signDoc == null) {
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
            log.warn("[CustomsDocumentService] Failed to render shipper sign PDF to PNG", e);
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
            log.warn("[CustomsDocumentService] Failed to convert shipper sign image to PNG", e);
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ─── 기존 문서 로드 ───

    private byte[] loadShipperIdentityPdf(Long companyId, Vehicle vehicle) {
        return findLatestShipperIdentityDocument(companyId, vehicle)
                .map(d -> loadAssPdf(d))
                .orElse(null);
    }

    private byte[] loadDeregistrationPdf(Long companyId, Vehicle vehicle) {
        return findLatestDeregistrationDocument(companyId, vehicle)
                .map(d -> loadAssPdf(d))
                .orElse(null);
    }

    private Optional<Document> findLatestShipperIdentityDocument(Long companyId, Vehicle vehicle) {
        if (vehicle.getShipperId() == null) {
            return Optional.empty();
        }
        DocumentType requiredDocType = resolveRequiredShipperDocType(companyId, vehicle);
        return documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, vehicle.getShipperId(), requiredDocType
        );
    }

    private DocumentType resolveRequiredShipperDocType(Long companyId, Vehicle vehicle) {
        if (vehicle == null || vehicle.getShipperId() == null) {
            return DocumentType.ID_CARD;
        }
        Shipper shipper = shipperRepo.findByIdAndCompanyId(vehicle.getShipperId(), companyId).orElse(null);
        if (shipper != null && shipper.getShipperType() == ShipperType.CORPORATE_BUSINESS) {
            return DocumentType.BIZ_REGISTRATION;
        }
        return DocumentType.ID_CARD;
    }

    private Optional<Document> findLatestDeregistrationDocument(Long companyId, Vehicle vehicle) {
        return documentRepo
                .findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                        companyId, DocumentRefType.VEHICLE, vehicle.getId())
                .stream()
                .filter(d -> d.getType() == DocumentType.DEREGISTRATION)
                .findFirst();
    }

    /**
     * S3 에서 문서를 가져와 PDF 로 변환 (이미지면 변환, PDF면 그대로).
     */
    private byte[] loadAssPdf(Document doc) {
        byte[] bytes = s3Reader.readBytes(doc.getS3Key());
        if (bytes == null) return null;

        String ct = doc.getContentType();
        if (ct != null && ct.startsWith("image/")) {
            return imageToPdf(bytes, ct);
        }
        return bytes; // already PDF
    }

    // ─── PDF 유틸 ───

    private byte[] imageToPdf(byte[] imageBytes, String contentType) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDImageXObject image = PDImageXObject.createFromByteArray(document, imageBytes, "image");

            float pageW = PDRectangle.A4.getWidth();
            float pageH = PDRectangle.A4.getHeight();
            float margin = 36;
            float maxW = pageW - 2 * margin;
            float maxH = pageH - 2 * margin;

            float scale = Math.min(maxW / image.getWidth(), maxH / image.getHeight());
            float drawW = image.getWidth() * scale;
            float drawH = image.getHeight() * scale;
            float x = (pageW - drawW) / 2;
            float y = (pageH - drawH) / 2;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.drawImage(image, x, y, drawW, drawH);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();

        } catch (IOException e) {
            log.error("[CustomsDocumentService] Image to PDF conversion failed", e);
            return null;
        }
    }

    private byte[] mergePdfs(List<byte[]> pdfs) {
        if (pdfs.isEmpty()) return new byte[0];
        if (pdfs.size() == 1) return pdfs.get(0);

        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (byte[] pdf : pdfs) {
                merger.addSource(new ByteArrayInputStream(pdf));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            merger.setDestinationStream(out);
            merger.mergeDocuments(null);
            return out.toByteArray();

        } catch (IOException e) {
            log.error("[CustomsDocumentService] PDF merge failed", e);
            throw new IllegalStateException("PDF merge failed", e);
        }
    }

    private static String safe(String s) {
        return s != null ? s.replaceAll("[^a-zA-Z0-9]", "") : "unknown";
    }

    private String buildGeneratedDocKey(Long companyId, Long requestId, String filename) {
        String safeFilename = filename == null || filename.isBlank()
                ? "document.pdf"
                : filename.replaceAll("[\\\\/<>:\"|?*]", "_");
        return GENERATED_DOCS_PREFIX
                + companyId + "/"
                + requestId + "/"
                + GENERATED_DOCS_CACHE_VERSION + "/"
                + safeFilename;
    }
}
