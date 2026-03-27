package exps.cariv.domain.shipper.service;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.shipper.dto.response.ShipperDetailResponse;
import exps.cariv.domain.shipper.dto.response.ShipperDetailResponse.ShipperDocInfo;
import exps.cariv.domain.shipper.dto.response.ShipperRequiredDocsResponse;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.entity.ShipperType;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.tenant.aspect.TenantFiltered;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@TenantFiltered
public class ShipperQueryService {

    private final ShipperRepository shipperRepo;
    private final DocumentRepository documentRepo;

    /**
     * 화주 관리 페이지: 모든 활성 화주 + 각 화주별 문서 목록.
     * 문서를 IN절 배치 조회로 N+1 방지.
     */
    public List<ShipperDetailResponse> listAll(Long companyId, String query) {
        List<Shipper> shippers = (query == null || query.isBlank())
                ? shipperRepo.findAllByCompanyIdAndActiveTrue(companyId)
                : shipperRepo.searchActive(companyId, query.trim());

        if (shippers.isEmpty()) return List.of();

        // 한 번의 쿼리로 모든 화주 문서를 가져온 뒤 groupBy
        List<Long> shipperIds = shippers.stream().map(Shipper::getId).toList();
        List<Document> allDocs = documentRepo.findAllByCompanyIdAndRefTypeAndRefIdIn(
                companyId, DocumentRefType.SHIPPER, shipperIds);
        Map<Long, List<Document>> docsByShipperId = allDocs.stream()
                .collect(Collectors.groupingBy(Document::getRefId));

        return shippers.stream()
                .map(s -> toDetailFromDocs(s, docsByShipperId.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    /**
     * 차량 등록 전 화주 필수 문서 업로드 상태를 사전 점검한다.
     * 타입별 개별 쿼리 대신 IN절 배치 조회로 N+1 방지.
     */
    public ShipperRequiredDocsResponse getRequiredDocsStatus(Long companyId, Long shipperId) {
        Shipper shipper = shipperRepo.findByIdAndCompanyIdAndActiveTrue(shipperId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.SHIPPER_NOT_FOUND));

        List<DocumentType> requiredTypes = requiredTypes(shipper.getShipperType());

        // 필수 타입 + BIZ_ID_COMBINED 까지 한 번에 조회
        java.util.HashSet<DocumentType> lookupTypes = new java.util.HashSet<>(requiredTypes);
        lookupTypes.add(DocumentType.BIZ_ID_COMBINED);

        Set<DocumentType> existingTypes = new java.util.HashSet<>(documentRepo.findExistingTypes(
                companyId, DocumentRefType.SHIPPER, shipperId, lookupTypes
        ));

        // 합본(BIZ_ID_COMBINED)이 있으면 사업자등록증 + 대표자 신분증 충족 처리
        if (existingTypes.contains(DocumentType.BIZ_ID_COMBINED)) {
            existingTypes.add(DocumentType.BIZ_REGISTRATION);
            existingTypes.add(DocumentType.ID_CARD);
        }

        List<String> missing = requiredTypes.stream()
                .filter(type -> !existingTypes.contains(type))
                .map(this::toApiType)
                .toList();

        List<String> required = requiredTypes.stream().map(this::toApiType).toList();
        return new ShipperRequiredDocsResponse(
                shipperId,
                shipper.getShipperType() == null ? null : shipper.getShipperType().name(),
                missing.isEmpty(),
                required,
                missing
        );
    }

    /**
     * 개별 화주 조회 시 사용 (단건).
     */
    private ShipperDetailResponse toDetail(Long companyId, Shipper s) {
        List<Document> docs = documentRepo
                .findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                        companyId, DocumentRefType.SHIPPER, s.getId());
        return toDetailFromDocs(s, docs);
    }

    /**
     * 배치 조회된 문서 리스트로 화주 상세 생성 (N+1 방지용).
     */
    private ShipperDetailResponse toDetailFromDocs(Shipper s, List<Document> docs) {
        // 문서 슬롯은 타입별 1건이므로 최신 업로드 1건만 노출한다.
        Map<DocumentType, Document> latestByType = new LinkedHashMap<>();
        for (Document doc : docs) {
            latestByType.putIfAbsent(doc.getType(), doc);
        }

        List<ShipperDocInfo> docInfos = latestByType.values().stream()
                .map(d -> new ShipperDocInfo(
                        d.getId(), toApiType(d.getType()), d.getS3Key(),
                        d.getOriginalFilename(), d.getSizeBytes(), d.getUploadedAt()
                )).toList();

        return new ShipperDetailResponse(
                s.getId(), s.getName(),
                s.getShipperType() == null ? null : s.getShipperType().name(),
                s.getPhone(),
                s.getAddress(),
                s.isActive(), docInfos
        );
    }

    private String toApiType(DocumentType type) {
        return switch (type) {
            case ID_CARD -> "CEO_ID";
            case BIZ_REGISTRATION -> "BIZ_REG";
            default -> type.name();
        };
    }

    private List<DocumentType> requiredTypes(ShipperType shipperType) {
        if (shipperType == ShipperType.INDIVIDUAL_BUSINESS) {
            return List.of(DocumentType.BIZ_REGISTRATION, DocumentType.ID_CARD, DocumentType.SIGN);
        }
        return List.of(DocumentType.BIZ_REGISTRATION, DocumentType.SIGN);
    }
}
