package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.dto.request.CustomsListRequest;
import exps.cariv.domain.customs.dto.response.CustomsDetailResponse;
import exps.cariv.domain.customs.dto.response.CustomsDetailResponse.*;
import exps.cariv.domain.customs.dto.response.CustomsListResponse;
import exps.cariv.domain.customs.entity.*;
import exps.cariv.domain.customs.repository.CustomsRequestVehicleRepository;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
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

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@TenantFiltered
public class CustomsQueryService {

    private final VehicleRepository vehicleRepo;
    private final CustomsRequestVehicleRepository requestVehicleRepo;
    private final DocumentRepository documentRepo;

    /**
     * 신고필증 목록 조회 (필터 + 페이징).
     * <p>범위: BEFORE_REPORT + BEFORE_CERTIFICATE 단계 차량.</p>
     * <p>상태: 대기(관세사 전송 전), 진행(전송 후 신고필증 미발급), 완료(신고필증 업로드 완료)</p>
     */
    public Page<CustomsListResponse> list(Long companyId, CustomsListRequest req) {
        CustomsStatus filterStatus = parseCustomsStatus(req.stage());

        List<VehicleStage> stages = Arrays.asList(
                VehicleStage.BEFORE_REPORT,
                VehicleStage.BEFORE_CERTIFICATE
        );

        Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                .and(VehicleSpecification.notDeleted())
                .and(VehicleSpecification.stageIn(stages))
                .and(VehicleSpecification.shipperNameIs(req.shipperName()))
                .and(VehicleSpecification.keywordLike(req.query()))
                .and(VehicleSpecification.createdAfter(req.from()))
                .and(VehicleSpecification.createdBefore(req.to()));

        if (filterStatus != null) {
            spec = spec.and(statusSpecification(filterStatus));
        }

        PageRequest pageable = PageRequest.of(req.page(), req.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Vehicle> vehicles = vehicleRepo.findAll(spec, pageable);

        // 차량 ID → 최신 CustomsRequestVehicle 매핑 (EntityGraph 으로 CustomsRequest 함께 로딩)
        List<Long> vehicleIds = vehicles.getContent().stream()
                .map(Vehicle::getId).toList();
        Map<Long, CustomsRequestVehicle> crvMap = buildCrvMap(vehicleIds);

        // 수출신고필증 업로드 여부 (단일 쿼리)
        Set<Long> vehicleIdsWithExportDoc = findVehiclesWithExportDoc(companyId, vehicleIds);

        return vehicles.map(v -> {
            CustomsRequestVehicle crv = crvMap.get(v.getId());
            // EntityGraph 으로 이미 로딩됨 — 추가 쿼리 없음
            CustomsRequest cr = crv != null ? crv.getCustomsRequest() : null;
            boolean hasExportDoc = vehicleIdsWithExportDoc.contains(v.getId());
            boolean hasCustomsRequest = cr != null;

            CustomsStatus status = CustomsStatus.from(v.getStage(), hasExportDoc, hasCustomsRequest);
            Long requestId = cr != null ? cr.getId() : null;
            String requestStatus = cr != null ? cr.getStatus().name() : null;
            boolean canResend = cr != null && cr.getStatus() == CustomsRequestStatus.PROCESSING;

            return new CustomsListResponse(
                    v.getId(),
                    v.getStage().name(),
                    status.name(),
                    status.getLabel(),
                    v.getCreatedAt(),
                    v.getVehicleNo(),
                    v.getModelName(),
                    v.getVin(),
                    v.getModelYear(),
                    v.getShipperName(),
                    v.getShippingMethod(),
                    v.getDeRegistrationDate(),
                    requestId,
                    requestStatus,
                    canResend
            );
        });
    }

    /**
     * 신고필증 차량 상세 조회.
     */
    public CustomsDetailResponse detail(Long companyId, Long vehicleId) {
        Vehicle v = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        // 연결된 전송 요청
        CustomsRequestInfo crInfo = buildCustomsRequestInfo(vehicleId);

        // 문서 목록
        List<Document> docs = documentRepo.findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                companyId, DocumentRefType.VEHICLE, vehicleId);
        List<DocInfo> docInfos = docs.stream()
                .map(d -> new DocInfo(
                        d.getId(), d.getType().name(), d.getStatus().name(),
                        d.getS3Key(), d.getOriginalFilename(), d.getSizeBytes(), d.getUploadedAt()
                )).toList();

        // 상태 계산
        boolean hasExportDoc = docs.stream().anyMatch(d -> d.getType() == DocumentType.EXPORT_CERTIFICATE);
        boolean hasCustomsRequest = crInfo != null;
        CustomsStatus status = CustomsStatus.from(v.getStage(), hasExportDoc, hasCustomsRequest);

        return new CustomsDetailResponse(
                v.getId(), v.getStage().name(), v.getCreatedAt(),
                v.getVin(), v.getVehicleNo(), v.getCarType(),
                v.getModelName(), v.getModelYear(),
                v.getShipperName(),
                v.getShippingMethod(), v.getDeRegistrationDate(),
                status.name(), status.getLabel(),
                crInfo, docInfos
        );
    }

    // ─── private helpers ───

    private Map<Long, CustomsRequestVehicle> buildCrvMap(List<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) return Map.of();
        List<CustomsRequestVehicle> crvs = requestVehicleRepo.findAllByVehicleIdIn(vehicleIds);
        Map<Long, CustomsRequestVehicle> map = new HashMap<>();
        for (CustomsRequestVehicle crv : crvs) {
            map.merge(crv.getVehicleId(), crv, (old, nw) ->
                    nw.getCreatedAt().isAfter(old.getCreatedAt()) ? nw : old);
        }
        return map;
    }

    private Set<Long> findVehiclesWithExportDoc(Long companyId, List<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) return Set.of();
        // 단일 쿼리로 수출신고필증 보유 차량 ID 일괄 조회 (N+1 방지)
        return documentRepo.findRefIdsHavingType(
                companyId, DocumentRefType.VEHICLE, vehicleIds, DocumentType.EXPORT_CERTIFICATE);
    }

    private CustomsStatus parseCustomsStatus(String stage) {
        if (stage == null || stage.isBlank()) return null;
        String normalized = stage.trim().toUpperCase();
        return switch (normalized) {
            case "WAITING" -> CustomsStatus.WAITING;
            case "IN_PROGRESS" -> CustomsStatus.IN_PROGRESS;
            case "DONE" -> CustomsStatus.DONE;
            default -> null;
        };
    }

    private Specification<Vehicle> statusSpecification(CustomsStatus filterStatus) {
        return switch (filterStatus) {
            case DONE -> VehicleSpecification.hasVehicleDocument(DocumentType.EXPORT_CERTIFICATE);
            case IN_PROGRESS -> VehicleSpecification.noVehicleDocument(DocumentType.EXPORT_CERTIFICATE)
                    .and(hasCustomsRequest());
            case WAITING -> VehicleSpecification.noVehicleDocument(DocumentType.EXPORT_CERTIFICATE)
                    .and(noCustomsRequest());
        };
    }

    private Specification<Vehicle> hasCustomsRequest() {
        return (root, query, cb) -> {
            var sq = query.subquery(Long.class);
            var crv = sq.from(CustomsRequestVehicle.class);
            sq.select(crv.get("id")).where(
                    cb.equal(crv.get("vehicleId"), root.get("id")),
                    cb.equal(crv.get("customsRequest").get("companyId"), root.get("companyId"))
            );
            return cb.exists(sq);
        };
    }

    private Specification<Vehicle> noCustomsRequest() {
        Specification<Vehicle> hasCustoms = hasCustomsRequest();
        return (root, query, cb) -> cb.not(hasCustoms.toPredicate(root, query, cb));
    }

    private CustomsRequestInfo buildCustomsRequestInfo(Long vehicleId) {
        return requestVehicleRepo.findFirstByVehicleIdOrderByCreatedAtDesc(vehicleId)
                .map(crv -> {
                    CustomsRequest cr = crv.getCustomsRequest();
                    List<CustomsRequestVehicle> items = requestVehicleRepo.findAllByCustomsRequestId(cr.getId());

                    List<VehicleItemInfo> vehicleItems = items.stream()
                            .map(item -> new VehicleItemInfo(
                                    item.getId(), item.getVehicleId(),
                                    item.getPrice(),
                                    item.getTradeCondition() != null ? item.getTradeCondition().name() : null,
                                    item.getShippingFee(), item.getInsuranceFee(), item.getOtherFee(),
                                    item.getVehiclePhoto1S3Key(), item.getVehiclePhoto2S3Key(),
                                    item.getVehiclePhoto3S3Key(), item.getVehiclePhoto4S3Key()
                            )).toList();

                    ContainerInfoResponse ciResp = null;
                    if (cr.getContainerInfo() != null) {
                        ContainerInfo ci = cr.getContainerInfo();
                        ciResp = new ContainerInfoResponse(
                                ci.getContainerNo(), ci.getSealNo(), ci.getEntryPort(),
                                ci.getWarehouseLocation(),
                                ci.getVesselName(), ci.getExportPort(),
                                ci.getDestinationCountry(), ci.getConsignee()
                        );
                    }

                    List<String> containerPhotoKeys = new ArrayList<>();
                    if (cr.getContainerPhoto1S3Key() != null && !cr.getContainerPhoto1S3Key().isBlank()) {
                        containerPhotoKeys.add(cr.getContainerPhoto1S3Key());
                    }
                    if (cr.getContainerPhoto2S3Key() != null && !cr.getContainerPhoto2S3Key().isBlank()) {
                        containerPhotoKeys.add(cr.getContainerPhoto2S3Key());
                    }
                    if (cr.getContainerPhoto3S3Key() != null && !cr.getContainerPhoto3S3Key().isBlank()) {
                        containerPhotoKeys.add(cr.getContainerPhoto3S3Key());
                    }

                    return new CustomsRequestInfo(
                            cr.getId(),
                            toUiRequestStatus(cr.getStatus()),
                            cr.getStatus().name(),
                            cr.getStatus() == CustomsRequestStatus.PROCESSING,
                            cr.getCustomsBrokerName(),
                            cr.getShippingMethod() == null ? null : cr.getShippingMethod().name(),
                            vehicleItems, ciResp, containerPhotoKeys
                    );
                }).orElse(null);
    }

    private String toUiRequestStatus(CustomsRequestStatus status) {
        return switch (status) {
            case DRAFT -> "WAITING";
            case SUBMITTED, PROCESSING -> "IN_PROGRESS";
            case COMPLETED -> "DONE";
        };
    }
}
