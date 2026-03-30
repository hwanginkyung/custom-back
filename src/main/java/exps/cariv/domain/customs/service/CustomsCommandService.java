package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.dto.request.CustomsSendRequest;
import exps.cariv.domain.customs.dto.request.CustomsSendRequest.ContainerInfoRequest;
import exps.cariv.domain.customs.dto.request.CustomsSendRequest.VehicleItem;
import exps.cariv.domain.customs.entity.*;
import exps.cariv.domain.customs.repository.CustomsBrokerRepository;
import exps.cariv.domain.customs.repository.CustomsRequestRepository;
import exps.cariv.domain.customs.repository.CustomsRequestVehicleRepository;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomsCommandService {

    private final CustomsBrokerRepository brokerRepo;
    private final CustomsRequestRepository requestRepo;
    private final CustomsRequestVehicleRepository requestVehicleRepo;
    private final VehicleRepository vehicleRepo;

    /**
     * 관세사 전송용 초안(DRAFT) 생성.
     * <p>최소 단위로 requestId 만 발급하기 위해 빈 DRAFT 를 생성한다.</p>
     *
     * @return 생성된 CustomsRequest ID
     */
    public Long createDraft(Long companyId) {
        CustomsRequest cr = CustomsRequest.builder()
                .shippingMethod(null)
                .status(CustomsRequestStatus.DRAFT)
                .build();
        cr = requestRepo.save(cr);
        return cr.getId();
    }

    /**
     * 초안(DRAFT) 요청을 최종 전송(PROCESSING)한다.
     */
    public void submitDraft(Long companyId, Long requestId, CustomsSendRequest req) {
        validateBaseInput(req);

        CustomsRequest cr = requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (cr.getStatus() == CustomsRequestStatus.PROCESSING) {
            ShippingMethod method = parseShippingMethod(req.shippingMethod());
            CustomsBroker broker = resolveBrokerIfProvided(companyId, req.customsBrokerId());
            boolean requirePhotosForContainer = shouldRequireContainerPhotos(broker, req.customsBrokerName());
            ContainerInfo containerInfo = buildContainerInfo(method, req.containerInfo());
            List<String> containerPhotoKeys = normalizeContainerPhotoKeys(
                    method,
                    req.containerPhotoS3Keys(),
                    requirePhotosForContainer
            );

            cr.updateForResend(
                    method,
                    broker == null ? null : broker.getId(),
                    firstNonBlank(
                            broker == null ? null : broker.getName(),
                            req.customsBrokerName()
                    ),
                    containerInfo,
                    getPhoto(containerPhotoKeys, 0),
                    getPhoto(containerPhotoKeys, 1),
                    getPhoto(containerPhotoKeys, 2)
            );

            requestVehicleRepo.deleteAllByCustomsRequestId(requestId);
            upsertRequestItems(companyId, cr, method, req.vehicles(), requirePhotosForContainer, false);

            List<CustomsRequestVehicle> items = requestVehicleRepo.findAllByCustomsRequestId(requestId);
            if (items.isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "전송할 차량이 없습니다.");
            }
            validateRequiredPhotosForSubmit(cr, items, requirePhotosForContainer);

            for (CustomsRequestVehicle item : items) {
                Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(item.getVehicleId(), companyId)
                        .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

                vehicle.updateShippingMethod(cr.getShippingMethod().name());
                if (vehicle.getStage() == VehicleStage.BEFORE_REPORT) {
                    vehicle.updateStage(VehicleStage.BEFORE_CERTIFICATE);
                }
                vehicleRepo.save(vehicle);
            }
            return;
        }
        // 하위호환: 과거 SUBMITTED 데이터는 즉시 PROCESSING 으로 승격
        if (cr.getStatus() == CustomsRequestStatus.SUBMITTED) {
            cr.startProcessing();
            return;
        }
        if (cr.getStatus() != CustomsRequestStatus.DRAFT) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "DRAFT 상태 요청만 submit 할 수 있습니다.");
        }

        ShippingMethod method = parseShippingMethod(req.shippingMethod());
        CustomsBroker broker = resolveBrokerIfProvided(companyId, req.customsBrokerId());
        boolean requirePhotosForContainer = shouldRequireContainerPhotos(broker, req.customsBrokerName());
        ContainerInfo containerInfo = buildContainerInfo(method, req.containerInfo());
        List<String> containerPhotoKeys = normalizeContainerPhotoKeys(
                method,
                req.containerPhotoS3Keys(),
                requirePhotosForContainer
        );

        cr.updateDraft(
                method,
                broker == null ? null : broker.getId(),
                firstNonBlank(
                        broker == null ? null : broker.getName(),
                        req.customsBrokerName()
                ),
                containerInfo,
                getPhoto(containerPhotoKeys, 0),
                getPhoto(containerPhotoKeys, 1),
                getPhoto(containerPhotoKeys, 2)
        );

        requestVehicleRepo.deleteAllByCustomsRequestId(requestId);
        upsertRequestItems(companyId, cr, method, req.vehicles(), requirePhotosForContainer, false);

        List<CustomsRequestVehicle> items = requestVehicleRepo.findAllByCustomsRequestId(requestId);
        if (items.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "전송할 차량이 없습니다.");
        }
        validateRequiredPhotosForSubmit(cr, items, requirePhotosForContainer);

        for (CustomsRequestVehicle item : items) {
            Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(item.getVehicleId(), companyId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

            vehicle.updateShippingMethod(cr.getShippingMethod().name());
            if (vehicle.getStage() == VehicleStage.BEFORE_REPORT) {
                vehicle.updateStage(VehicleStage.BEFORE_CERTIFICATE);
            }
            vehicleRepo.save(vehicle);
        }

        cr.startProcessing();
    }

    /**
     * 다시 보내기 — 기존 전송 요청을 수정하여 재전송.
     * <p>진행(IN_PROGRESS) 상태인 차량에 대해 기존 CustomsRequest 를 PROCESSING 으로 유지하며
     * 차량 아이템을 새로 교체한다.</p>
     */
    public Long resendRequest(Long companyId, Long requestId, CustomsSendRequest req) {
        validateBaseInput(req);

        CustomsRequest existing = requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (existing.getStatus() != CustomsRequestStatus.PROCESSING) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT,
                    "PROCESSING 상태 요청만 재전송할 수 있습니다."
            );
        }
        CustomsBroker broker = resolveBrokerIfProvided(companyId, req.customsBrokerId());
        boolean requirePhotosForContainer = shouldRequireContainerPhotos(broker, req.customsBrokerName());

        // 기존 차량 아이템 삭제
        requestVehicleRepo.deleteAllByCustomsRequestId(requestId);

        // 선적방식 파싱
        ShippingMethod method = parseShippingMethod(req.shippingMethod());

        // 컨테이너 정보
        ContainerInfo containerInfo = buildContainerInfo(method, req.containerInfo());
        List<String> containerPhotoKeys = normalizeContainerPhotoKeys(
                method,
                req.containerPhotoS3Keys(),
                requirePhotosForContainer
        );

        // managed 엔티티 직접 업데이트 (detached entity + audit 필드 유실 방지)
        existing.updateForResend(
                method,
                broker == null ? null : broker.getId(),
                firstNonBlank(
                        broker == null ? null : broker.getName(),
                        req.customsBrokerName()
                ),
                containerInfo,
                getPhoto(containerPhotoKeys, 0),
                getPhoto(containerPhotoKeys, 1),
                getPhoto(containerPhotoKeys, 2)
        );

        // 새 차량 아이템 생성 (resend 에서는 사진 필수)
        upsertRequestItems(companyId, existing, method, req.vehicles(), requirePhotosForContainer, true);

        return existing.getId();
    }

    /**
     * 관세사 전송(mock).
     * <p>현재 신규 플로우에서는 submit 시점에 이미 PROCESSING 이므로 대부분 idempotent 응답이다.</p>
     * <ul>
     *     <li>DRAFT: 전송 불가</li>
     *     <li>SUBMITTED: (레거시 호환) PROCESSING 으로 전이</li>
     *     <li>PROCESSING/COMPLETED: 현재 상태 반환 (idempotent)</li>
     * </ul>
     */
    public CustomsRequestStatus sendMock(Long companyId, Long requestId) {
        CustomsRequest request = requestRepo.findByIdAndCompanyId(requestId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "통관 요청을 찾을 수 없습니다."));

        if (request.getStatus() == CustomsRequestStatus.DRAFT) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "임시저장 상태(DRAFT)에서는 보낼 수 없습니다. submit 후 다시 시도해주세요.");
        }

        if (request.getStatus() == CustomsRequestStatus.SUBMITTED) {
            request.startProcessing();
            return CustomsRequestStatus.PROCESSING;
        }

        return request.getStatus();
    }

    private CustomsBroker findBrokerOrThrow(Long companyId, Long brokerId) {
        return brokerRepo.findByIdAndCompanyIdAndActiveTrue(brokerId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "관세사 정보를 찾을 수 없습니다."));
    }

    private CustomsBroker resolveBrokerIfProvided(Long companyId, Long brokerId) {
        if (brokerId == null || brokerId <= 0) {
            return null;
        }
        return findBrokerOrThrow(companyId, brokerId);
    }

    private ShippingMethod parseShippingMethod(String raw) {
        try {
            ShippingMethod method = ShippingMethod.valueOf(raw.trim().toUpperCase());
            if (method == ShippingMethod.UNKNOWN) {
                throw new IllegalArgumentException("UNKNOWN is not allowed from client input");
            }
            return method;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "선적방식은 RORO 또는 CONTAINER 이어야 합니다.");
        }
    }

    private ContainerInfo buildContainerInfo(ShippingMethod method, ContainerInfoRequest ci) {
        if (method == ShippingMethod.RORO) {
            if (ci == null) {
                return null;
            }
            return ContainerInfo.builder()
                    .warehouseLocation(firstNonBlank(ci.warehouseLocation()))
                    .exportPort(firstNonBlank(ci.exportPort()))
                    .destinationCountry(firstNonBlank(ci.destinationCountry()))
                    .consignee(firstNonBlank(ci.consignee()))
                    .build();
        }

        if (ci == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "CONTAINER 선적에서는 컨테이너 정보가 필요합니다.");
        }

        return ContainerInfo.builder()
                .containerNo(ci.containerNo())
                .sealNo(ci.sealNo())
                .entryPort(ci.entryPort())
                .warehouseLocation(ci.warehouseLocation())
                .vesselName(ci.vesselName())
                .exportPort(ci.exportPort())
                .destinationCountry(ci.destinationCountry())
                .consignee(ci.consignee())
                .build();
    }

    private void validateBaseInput(CustomsSendRequest req) {
        if (req.shippingMethod() == null || req.shippingMethod().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        if (req.vehicles() == null || req.vehicles().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
    }

    private void upsertRequestItems(Long companyId,
                                    CustomsRequest request,
                                    ShippingMethod method,
                                    List<VehicleItem> vehicles,
                                    boolean requirePhotosForContainer,
                                    boolean updateVehicleShippingMethod) {
        for (VehicleItem vi : vehicles) {
            Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vi.vehicleId(), companyId)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

            TradeCondition tc = parseTradeCondition(vi.tradeCondition());
            List<String> vehiclePhotoKeys = normalizeVehiclePhotoKeys(method, vi.vehiclePhotoS3Keys(), requirePhotosForContainer);

            CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                    .customsRequest(request)
                    .vehicleId(vi.vehicleId())
                    .price(vi.price())
                    .tradeCondition(tc)
                    .shippingFee(vi.shippingFee())
                    .insuranceFee(vi.insuranceFee())
                    .otherFee(vi.otherFee())
                    .vehiclePhoto1S3Key(getPhoto(vehiclePhotoKeys, 0))
                    .vehiclePhoto2S3Key(getPhoto(vehiclePhotoKeys, 1))
                    .vehiclePhoto3S3Key(getPhoto(vehiclePhotoKeys, 2))
                    .vehiclePhoto4S3Key(getPhoto(vehiclePhotoKeys, 3))
                    .build();
            requestVehicleRepo.save(crv);

            // 중량 업데이트: 사용자가 입력한 값이 있으면 Vehicle 엔티티도 갱신
            if (vi.weight() != null) {
                vehicle.patchWeight(vi.weight());
            }

            if (updateVehicleShippingMethod) {
                vehicle.updateShippingMethod(method.name());
            }
            vehicleRepo.save(vehicle);
        }
    }

    private void validateRequiredPhotosForSubmit(CustomsRequest request,
                                                 List<CustomsRequestVehicle> items,
                                                 boolean requiredForContainer) {
        if (!requiredForContainer) {
            return;
        }

        if (request.getShippingMethod() != ShippingMethod.CONTAINER) {
            return;
        }

        if (!hasAnyValue(
                request.getContainerPhoto1S3Key(),
                request.getContainerPhoto2S3Key(),
                request.getContainerPhoto3S3Key())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "CONTAINER 선적에서는 컨테이너 사진이 최소 1장 필요합니다.");
        }

        for (CustomsRequestVehicle item : items) {
            if (!hasAnyValue(
                    item.getVehiclePhoto1S3Key(),
                    item.getVehiclePhoto2S3Key(),
                    item.getVehiclePhoto3S3Key(),
                    item.getVehiclePhoto4S3Key())) {
                throw new CustomException(
                        ErrorCode.INVALID_INPUT,
                        "CONTAINER 선적에서는 차량별 사진이 최소 1장 필요합니다. vehicleId=" + item.getVehicleId()
                );
            }
        }
    }

    private boolean hasAnyValue(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalizeContainerPhotoKeys(ShippingMethod method, List<String> rawKeys, boolean requiredForContainer) {
        List<String> keys = normalizePhotoKeys(rawKeys, 3, "컨테이너 사진");

        // RORO에서는 containerPhotoS3Keys를 쇼링리스트 첨부 용도로 사용한다.
        if (method == ShippingMethod.RORO) return keys;

        if (requiredForContainer && keys.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "CONTAINER 선적에서는 컨테이너 사진이 최소 1장 필요합니다.");
        }
        return keys;
    }

    private List<String> normalizeVehiclePhotoKeys(ShippingMethod method, List<String> rawKeys, boolean requiredForContainer) {
        List<String> keys = normalizePhotoKeys(rawKeys, 4, "차량 사진");

        if (method == ShippingMethod.RORO) {
            if (!keys.isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "RORO 선적에서는 차량 사진을 입력할 수 없습니다.");
            }
            return List.of();
        }

        if (requiredForContainer && keys.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "CONTAINER 선적에서는 차량별 사진이 최소 1장 필요합니다.");
        }
        return keys;
    }

    private boolean shouldRequireContainerPhotos(CustomsBroker broker, String customsBrokerName) {
        if (broker != null) {
            return true;
        }
        return customsBrokerName != null && !customsBrokerName.isBlank();
    }

    private List<String> normalizePhotoKeys(List<String> rawKeys, int max, String fieldName) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return List.of();
        }

        List<String> keys = new ArrayList<>();
        for (String key : rawKeys) {
            if (key == null) continue;
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }

        if (keys.size() > max) {
            throw new CustomException(ErrorCode.INVALID_INPUT, fieldName + "은(는) 최대 " + max + "장까지 가능합니다.");
        }
        return keys;
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

    private String getPhoto(List<String> keys, int idx) {
        return idx < keys.size() ? keys.get(idx) : null;
    }

    private TradeCondition parseTradeCondition(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return TradeCondition.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT,
                    "거래조건이 올바르지 않습니다. 허용값: FOB, CIF, CFR"
            );
        }
    }
}
