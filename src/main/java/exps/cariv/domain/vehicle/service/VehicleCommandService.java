package exps.cariv.domain.vehicle.service;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.registration.dto.RegistrationParsed;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.entity.ShipperType;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.domain.vehicle.dto.VehiclePatch;
import exps.cariv.domain.vehicle.dto.request.VehicleCreateRequest;
import exps.cariv.domain.vehicle.dto.request.VehicleUpdateRequest;
import exps.cariv.domain.vehicle.dto.response.VehicleCreateResponse;
import exps.cariv.domain.vehicle.entity.OwnerType;
import exps.cariv.domain.vehicle.entity.TransmissionType;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleCommandService {

    private final VehicleRepository repo;
    private final VehicleDocumentService documentService;
    private final RegistrationDocumentRepository registrationDocRepo;
    private final DocumentRepository documentRepo;
    private final ShipperRepository shipperRepo;
    private final VehicleAsyncTasks vehicleAsyncTasks;

    @Transactional(readOnly = true)
    public Vehicle getByIdOrThrow(Long companyId, Long vehicleId) {
        return repo.findByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Vehicle getActiveOrThrow(Long companyId, Long vehicleId) {
        return repo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }

    /**
     * 차량 생성 (문서 업로드 후 호출).
     */
    @Transactional
    public VehicleCreateResponse createVehicle(Long companyId, VehicleCreateRequest req) {
        Shipper shipper = shipperRepo.findByIdAndCompanyIdAndActiveTrue(req.shipperId(), companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHIPPER_NOT_FOUND));
        OwnerType ownerType = parseOwnerType(req.ownerType());
        validateShipperDocuments(companyId, shipper);
        validateVehicleRequiredDocuments(companyId, ownerType, req.registrationDocumentId(), req.ownerIdCardDocumentId());

        Vehicle vehicle = Vehicle.builder()
                .shipperName(shipper.getName())
                .shipperId(shipper.getId())
                .ownerType(ownerType)
                .stage(VehicleStage.BEFORE_DEREGISTRATION)
                .build();
        vehicle.setCompanyId(companyId);

        vehicle = repo.save(vehicle);

        // 문서들을 차량에 연결
        documentService.linkDocumentsToVehicle(
                companyId,
                vehicle.getId(),
                req.registrationDocumentId(),
                req.ownerIdCardDocumentId(),
                ownerType
        );

        // 생성 시점에 등록증 OCR 스냅샷을 반영한다.
        CrossValidationResult crossValidation = applyCoreFieldsFromDocuments(
                vehicle,
                companyId,
                req.registrationDocumentId()
        );

        // 차량 등록 단계에서 말소 기본 문서는 백그라운드 선생성해 사용자 대기시간을 줄인다.
        vehicleAsyncTasks.preGenerateMalsoDocs(companyId, vehicle.getId());

        String reviewMessage = crossValidation.reviewNeeded()
                ? "문서 간 OCR 인식값이 일치하지 않아 검토가 필요합니다."
                : null;

        return new VehicleCreateResponse(
                vehicle.getId(),
                vehicle.getStage().name(),
                crossValidation.reviewNeeded(),
                crossValidation.mismatchFields(),
                reviewMessage
        );
    }

    /**
     * 차량 수정 (PATCH).
     */
    @Transactional
    public void updateVehicle(Long companyId, Long vehicleId, VehicleUpdateRequest req) {
        Vehicle v = getActiveOrThrow(companyId, vehicleId);

        VehiclePatch patch = new VehiclePatch(
                req.vin(), req.vehicleNo(), req.carType(), req.vehicleUse(),
                req.modelName(), req.engineType(), req.mileageKm(),
                req.ownerName(), req.ownerId(), req.modelYear(),
                parseOwnerTypeNullable(req.ownerType()),
                req.manufactureYearMonth(),
                req.displacement(), req.firstRegistrationDate(),
                parseTransmissionNullable(req.transmission()),
                req.fuelType(), req.color(),
                req.weight(), req.seatingCapacity(), req.length(), req.height(), req.width()
        );

        v.applyFullUpdate(
                patch,
                req.shipperName(),
                req.shipperId(),
                parseOwnerTypeNullable(req.ownerType()),
                req.purchasePrice(),
                req.purchaseDate(),
                req.saleAmount(),
                req.saleDate()
        );

        // stage 변경
        if (req.stage() != null && !req.stage().isBlank()) {
            try {
                v.updateStage(VehicleStage.valueOf(req.stage().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // 유효하지 않은 stage는 무시
            }
        }
    }

    /**
     * 차량 삭제 (soft delete).
     */
    @Transactional
    public void deleteVehicle(Long companyId, Long vehicleId) {
        Vehicle v = getActiveOrThrow(companyId, vehicleId);
        v.softDelete();
    }

    /**
     * 차량등록증 OCR 결과를 Vehicle 컬럼에 반영 (값이 null이면 기존 유지).
     */
    @Transactional
    public Vehicle applyRegistration(Long companyId, Long vehicleId, RegistrationParsed parsed) {
        Vehicle v = getByIdOrThrow(companyId, vehicleId);

        VehiclePatch patch = new VehiclePatch(
                parsed.vin(),
                parsed.vehicleNo(),
                parsed.carType(),
                parsed.vehicleUse(),
                parsed.modelName(),
                parsed.engineType(),
                parsed.mileageKm(),
                parsed.ownerName(),
                parsed.ownerId(),
                parsed.modelYear(),
                null,
                parsed.manufactureYearMonth(),
                parsed.displacement(),
                parsed.firstRegistratedAt(),
                null, parsed.fuelType(), null,
                parsed.weightKg(),
                parsed.seating(),
                parsed.lengthMm(),
                parsed.heightMm(),
                parsed.widthMm()
        );

        v.applyPatch(patch);
        return v;
    }

    private CrossValidationResult applyCoreFieldsFromDocuments(Vehicle vehicle,
                                                               Long companyId,
                                                               Long registrationDocumentId) {
        RegistrationDocument reg = registrationDocumentId == null ? null
                : registrationDocRepo.findByCompanyIdAndId(companyId, registrationDocumentId).orElse(null);
        if (reg == null) {
            return new CrossValidationResult(false, List.of());
        }

        VehiclePatch patch = new VehiclePatch(
                reg.getVin(),
                reg.getVehicleNo(),
                reg.getCarType(),
                reg.getVehicleUse(),
                reg.getModelName(),
                reg.getEngineType(),
                reg.getMileageKm(),
                reg.getOwnerName(),
                reg.getOwnerId(),
                reg.getModelYear(),
                null,
                reg.getManufactureYearMonth(),
                reg.getDisplacement(),
                reg.getFirstRegistratedAt(),
                null,
                reg.getFuelType(),
                null,
                parseInteger(reg.getWeight()),
                parseInteger(reg.getSeating()),
                parseInteger(reg.getLengthVal()),
                parseInteger(reg.getHeightVal()),
                parseInteger(reg.getWidthVal())
        );
        vehicle.applyPatch(patch);
        return new CrossValidationResult(false, List.of());
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().replaceAll("[^0-9]", "");
        if (normalized.isEmpty()) return null;
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private OwnerType parseOwnerType(String raw) {
        try {
            return OwnerType.from(raw);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    private OwnerType parseOwnerTypeNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return parseOwnerType(raw);
    }

    private TransmissionType parseTransmissionNullable(String raw) {
        try {
            return TransmissionType.from(raw);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    private void validateShipperDocuments(Long companyId, Shipper shipper) {
        if (shipper.getShipperType() == ShipperType.INDIVIDUAL_BUSINESS) {
            ensureShipperDocExists(companyId, shipper.getId(), DocumentType.BIZ_REGISTRATION);
            ensureShipperDocExists(companyId, shipper.getId(), DocumentType.ID_CARD);
            ensureShipperDocExists(companyId, shipper.getId(), DocumentType.SIGN);
            return;
        }
        ensureShipperDocExists(companyId, shipper.getId(), DocumentType.BIZ_REGISTRATION);
        ensureShipperDocExists(companyId, shipper.getId(), DocumentType.SIGN);
    }

    private void ensureShipperDocExists(Long companyId, Long shipperId, DocumentType type) {
        boolean exists = documentRepo.findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                companyId, DocumentRefType.SHIPPER, shipperId, type
        ).isPresent();
        if (!exists) {
            throw new CustomException(
                    ErrorCode.REQUIRED_DOCUMENT_MISSING,
                    "화주 필수문서가 누락되었습니다. shipperId=" + shipperId + ", type=" + type.name()
            );
        }
    }

    private void validateVehicleRequiredDocuments(Long companyId,
                                                  OwnerType ownerType,
                                                  Long registrationDocumentId,
                                                  Long ownerIdCardDocumentId) {
        if (registrationDocumentId == null) {
            throw new CustomException(ErrorCode.REQUIRED_DOCUMENT_MISSING, "자동차등록증(REGISTRATION)은 필수입니다.");
        }
        RegistrationDocument regDoc = registrationDocRepo.findByCompanyIdAndId(companyId, registrationDocumentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "자동차등록증 문서를 찾을 수 없습니다."));
        if (regDoc.getParsedAt() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "자동차등록증 OCR이 아직 완료되지 않았습니다.");
        }
        if (ownerType.isOwnerIdCardRequired() && ownerIdCardDocumentId == null) {
            throw new CustomException(ErrorCode.REQUIRED_DOCUMENT_MISSING, "소유자 신분증(ID_CARD)이 필수입니다.");
        }
    }

    private record CrossValidationResult(
            boolean reviewNeeded,
            List<String> mismatchFields
    ) {}
}
