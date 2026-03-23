package exps.cariv.domain.malso.service;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.malso.dto.request.MalsoListRequest;
import exps.cariv.domain.malso.dto.response.MalsoCompleteResponse;
import exps.cariv.domain.malso.dto.response.MalsoDetailResponse;
import exps.cariv.domain.malso.dto.response.MalsoListResponse;
import exps.cariv.domain.malso.entity.Deregistration;
import exps.cariv.domain.malso.entity.MalsoStatus;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.domain.malso.repository.DeregistrationRepository;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.entity.ShipperType;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.domain.vehicle.entity.OwnerType;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.repository.VehicleSpecification;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@TenantFiltered
public class MalsoQueryService {
    private static final Pattern OWNER_ID_PATTERN = Pattern.compile("(?<!\\d)\\d{6}-?\\d{7}(?!\\d)");
    private static final Pattern BUSINESS_NO_PATTERN = Pattern.compile("(?<!\\d)\\d{3}-?\\d{2}-?\\d{5}(?!\\d)");
    private static final Pattern BIRTH_DATE_PATTERN = Pattern.compile("(\\d{4})[.\\-/년\\s]+(\\d{1,2})[.\\-/월\\s]+(\\d{1,2})");

    private final VehicleRepository vehicleRepo;
    private final DeregistrationRepository deregRepo;
    private final OcrParseJobRepository ocrJobRepo;
    private final OcrResultNormalizer ocrResultNormalizer;
    private final ShipperRepository shipperRepo;

    /**
     * 말소 목록 조회 (필터 + 페이징).
     *
     * <p>필터:
     * - stage: 말소 상태 (WAITING / IN_PROGRESS / DONE) — MalsoStatus enum 이름
     * - shipperName: 화주명
     * - startDate / endDate: 등록일 범위
     */
    public Page<MalsoListResponse> list(Long companyId, MalsoListRequest req) {
        MalsoStatus filterStatus = parseMalsoStatus(req.stage());

        // 말소 페이지 범위: BEFORE_DEREGISTRATION(대기/진행) + BEFORE_REPORT(완료)
        List<VehicleStage> malsoStages = Arrays.asList(
                VehicleStage.BEFORE_DEREGISTRATION,
                VehicleStage.BEFORE_REPORT
        );

        Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                .and(VehicleSpecification.notDeleted())
                .and(VehicleSpecification.stageIn(malsoStages))
                .and(VehicleSpecification.shipperNameIs(req.shipperName()))
                .and(VehicleSpecification.createdAfter(req.startDate()))
                .and(VehicleSpecification.createdBefore(req.endDate()));

        if (filterStatus != null) {
            spec = spec.and(statusSpecification(filterStatus));
        }

        PageRequest pageable = PageRequest.of(req.page(), req.size(), Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Vehicle> vehicles = vehicleRepo.findAll(spec, pageable);

        // 배치: 페이지 내 전체 vehicleId에 대해 말소증 존재 여부 + 말소등록일을 한 번에 조회
        List<Long> vehicleIds = vehicles.getContent().stream().map(Vehicle::getId).toList();
        Set<Long> deregVehicleIds = vehicleIds.isEmpty()
                ? Set.of()
                : deregRepo.findRefIdsHavingDeregistration(companyId, DocumentRefType.VEHICLE, vehicleIds);

        Map<Long, LocalDate> deregDateMap = buildDeregDateMap(companyId, vehicleIds);

        return vehicles.map(v -> {
            boolean hasDereg = deregVehicleIds.contains(v.getId());
            MalsoStatus status = MalsoStatus.from(v.getStage(), hasDereg, v.hasMalsoPrintHistory());
            LocalDate deregDate = deregDateMap.get(v.getId());
            return toListResponse(v, hasDereg, status, deregDate);
        });
    }

    /**
     * 말소 차량 상세 조회.
     */
    public MalsoDetailResponse detail(Long companyId, Long vehicleId) {
        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        Shipper shipper = null;
        if (vehicle.getShipperId() != null) {
            shipper = shipperRepo.findByIdAndCompanyId(vehicle.getShipperId(), companyId).orElse(null);
        }
        OwnerType ownerType = vehicle.getOwnerType();
        ShipperType shipperType = shipper == null ? null : shipper.getShipperType();

        return new MalsoDetailResponse(
                vehicleId,
                ownerType == null ? null : ownerType.name(),
                shipperType == null ? null : shipperType.name(),
                resolveRequiredDocuments(ownerType, shipperType)
        );
    }

    /**
     * 말소 완료 상세 (알림 클릭 시).
     */
    public MalsoCompleteResponse completeDetail(Long companyId, Long vehicleId) {
        return completeDetailByVehicleId(companyId, vehicleId);
    }

    private MalsoCompleteResponse completeDetailByVehicleId(Long companyId, Long vehicleId) {
        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        Deregistration doc = deregRepo.findTopByCompanyIdAndRefTypeAndRefIdOrderByUploadedAtDescIdDesc(
                        companyId, DocumentRefType.VEHICLE, vehicleId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "Deregistration document not found."));

        OcrFieldResult ocr = ocrJobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, doc.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(OcrFieldResult::empty);

        Map<String, String> values = new LinkedHashMap<>(ocr.values());
        // 수동 수정값이 화면에 즉시 반영되도록 문서 저장값을 OCR 값보다 우선 적용한다.
        putOverride(values, "vehicleNo", doc.getRegistrationNo());
        putOverride(values, "vin", doc.getVin());
        putOverride(values, "modelName", doc.getModelName());
        putOverride(values, "modelYear", doc.getModelYear() == null ? null : String.valueOf(doc.getModelYear()));
        putOverride(values, "ownerName", doc.getOwnerName());
        putOverride(values, "ownerId", normalizeOwnerIdentifier(doc.getOwnerId()));
        putOverride(values, "deRegistrationDate",
                doc.getDeRegistrationDate() == null ? null : doc.getDeRegistrationDate().toString());
        // 문서값이 비어 있으면 vehicle 저장값으로 2차 보강
        putIfBlank(values, "vehicleNo", vehicle.getVehicleNo());
        putIfBlank(values, "vin", vehicle.getVin());
        putIfBlank(values, "modelName", vehicle.getModelName());
        putIfBlank(values, "modelYear", vehicle.getModelYear() == null ? null : String.valueOf(vehicle.getModelYear()));
        putIfBlank(values, "ownerName", vehicle.getOwnerName());
        putIfBlank(values, "ownerId", normalizeOwnerIdentifier(vehicle.getOwnerId()));
        putIfBlank(values, "deRegistrationDate",
                vehicle.getDeRegistrationDate() == null ? null : vehicle.getDeRegistrationDate().toString());

        Map<String, String> invalidFields = ocr.invalidFields();
        Set<String> missingFields = new LinkedHashSet<>(ocr.missingFields());

        List<MalsoCompleteResponse.FieldResult> fields = new ArrayList<>();
        fields.add(toField("vehicleNo", "Vehicle Number", values, invalidFields, missingFields));
        fields.add(toField("vin", "VIN", values, invalidFields, missingFields));
        fields.add(toField("modelName", "Model Name", values, invalidFields, missingFields));
        fields.add(toField("modelYear", "Model Year", values, invalidFields, missingFields));
        fields.add(toField("ownerName", "Owner Name", values, invalidFields, missingFields));
        fields.add(toField("ownerId", "Owner Birth Date (Corporate Registration No.)", values, invalidFields, missingFields));
        fields.add(toField("deRegistrationDate", "Deregistration Date", values, invalidFields, missingFields));

        int successCount = (int) fields.stream().filter(MalsoCompleteResponse.FieldResult::success).count();
        int errorCount = fields.size() - successCount;

        return new MalsoCompleteResponse(
                vehicleId,
                firstNonBlank(vehicle.getModelName(), vehicle.getVehicleNo(), "Vehicle Info"),
                new MalsoCompleteResponse.UploadedDocument(
                        doc.getId(),
                        doc.getS3Key(),
                        firstNonBlank(doc.getOriginalFilename(), null, "Deregistration"),
                        "DEREGISTRATION",
                        doc.getSizeBytes(),
                        doc.getUploadedAt(),
                        doc.getParsedAt()
                ),
                fields,
                new MalsoCompleteResponse.Summary(successCount, errorCount)
        );
    }

    private MalsoCompleteResponse.FieldResult toField(String key,
                                                      String label,
                                                      Map<String, String> values,
                                                      Map<String, String> invalidFields,
                                                      Set<String> missingFields) {
        String value = values.get(key);
        String invalidMessage = invalidFields.get(key);

        boolean hasInvalid = invalidMessage != null && !invalidMessage.isBlank();
        boolean hasMissing = value == null || value.isBlank();
        boolean success = !hasInvalid && !hasMissing;

        String errorMessage = null;
        if (!success) {
            errorMessage = hasInvalid ? invalidMessage : "Recognition failed - manual input required";
        }

        return new MalsoCompleteResponse.FieldResult(
                key,
                label,
                value,
                success,
                errorMessage
        );
    }

    private void putIfBlank(Map<String, String> target, String key, String value) {
        if (!target.containsKey(key) || target.get(key) == null || target.get(key).isBlank()) {
            target.put(key, value);
        }
    }

    private void putOverride(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return fallback;
    }

    private String normalizeOwnerIdentifier(String value) {
        if (value == null) return null;
        String compact = value.replaceAll("\\s+", "");
        if (compact.isBlank()) return null;

        Matcher ownerMatcher = OWNER_ID_PATTERN.matcher(compact);
        if (ownerMatcher.find()) {
            return ownerMatcher.group();
        }
        Matcher bizMatcher = BUSINESS_NO_PATTERN.matcher(compact);
        if (bizMatcher.find()) {
            return bizMatcher.group();
        }
        Matcher birthDateMatcher = BIRTH_DATE_PATTERN.matcher(compact);
        if (birthDateMatcher.find()) {
            try {
                int year = Integer.parseInt(birthDateMatcher.group(1));
                int month = Integer.parseInt(birthDateMatcher.group(2));
                int day = Integer.parseInt(birthDateMatcher.group(3));
                return String.format("%04d-%02d-%02d", year, month, day);
            } catch (NumberFormatException ignored) {
                // keep fallback null
            }
        }
        return null;
    }

    private MalsoListResponse toListResponse(Vehicle v, boolean hasDereg, MalsoStatus status,
                                              LocalDate deRegistrationDate) {
        return new MalsoListResponse(
                v.getId(), v.getStage().name(),
                status.name(), status.getLabel(),
                v.getCreatedAt(),
                v.getVehicleNo(), v.getModelName(), v.getVin(),
                v.getModelYear(), v.getCarType(),
                v.getShipperName(), v.getOwnerType() == null ? null : v.getOwnerType().name(),
                hasDereg,
                deRegistrationDate
        );
    }

    /** 배치: vehicleId → 말소등록일 맵 구성 */
    private Map<Long, LocalDate> buildDeregDateMap(Long companyId, List<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) return Map.of();

        Map<Long, LocalDate> map = new HashMap<>();
        for (Object[] row : deregRepo.findDeregDatesByRefIds(companyId, DocumentRefType.VEHICLE, vehicleIds)) {
            Long refId = (Long) row[0];
            LocalDate date = (LocalDate) row[1];
            map.put(refId, date);
        }
        return map;
    }

    private List<String> resolveRequiredDocuments(OwnerType ownerType, ShipperType shipperType) {
        List<String> docs = new ArrayList<>();
        docs.add("License Plate");
        docs.add("Vehicle Registration");

        if (ownerType == null) {
            docs.add("Additional owner-type specific documents");
        } else if (ownerType == OwnerType.INDIVIDUAL || ownerType == OwnerType.DEALER_INDIVIDUAL) {
            docs.add("Owner ID Card");
        } else {
            docs.add("Corporate ownership proof");
        }

        if (shipperType == null) {
            docs.add("Additional shipper-type specific documents");
        } else if (shipperType == ShipperType.CORPORATE_BUSINESS) {
            docs.add("Corporate Registry");
            docs.add("Signature Seal");
        } else {
            docs.add("Power of Attorney");
            docs.add("Signature Seal");
        }

        return docs;
    }

    private MalsoStatus parseMalsoStatus(String stage) {
        if (stage == null || stage.isBlank()) return null;
        String normalized = stage.trim().toUpperCase();

        return switch (normalized) {
            // 명세서 값
            case "REGISTERED_BY_DEALER" -> MalsoStatus.WAITING;
            case "DEREG_IN_PROGRESS" -> MalsoStatus.IN_PROGRESS;
            case "DEREG_COMPLETED" -> MalsoStatus.DONE;

            // 내부/레거시 값도 허용(하위호환)
            case "WAITING" -> MalsoStatus.WAITING;
            case "IN_PROGRESS" -> MalsoStatus.IN_PROGRESS;
            case "DONE" -> MalsoStatus.DONE;
            default -> null;
        };
    }

    private Specification<Vehicle> statusSpecification(MalsoStatus filterStatus) {
        return switch (filterStatus) {
            case DONE -> VehicleSpecification.hasVehicleDocument(DocumentType.DEREGISTRATION);
            case IN_PROGRESS -> VehicleSpecification.noVehicleDocument(DocumentType.DEREGISTRATION)
                    .and(VehicleSpecification.malsoPrinted(true));
            case WAITING -> VehicleSpecification.noVehicleDocument(DocumentType.DEREGISTRATION)
                    .and(VehicleSpecification.malsoPrinted(false));
        };
    }
}
