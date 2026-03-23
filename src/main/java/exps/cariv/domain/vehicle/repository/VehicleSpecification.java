package exps.cariv.domain.vehicle.repository;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 차량 목록 동적 필터 Specification.
 */
public final class VehicleSpecification {

    private VehicleSpecification() {}

    public static Specification<Vehicle> companyIs(Long companyId) {
        return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
    }

    public static Specification<Vehicle> notDeleted() {
        return (root, query, cb) -> cb.equal(root.get("deleted"), false);
    }

    public static Specification<Vehicle> stageIs(VehicleStage stage) {
        if (stage == null) return null;
        return (root, query, cb) -> cb.equal(root.get("stage"), stage);
    }

    /** stage가 주어진 목록 중 하나인 차량만 조회 */
    public static Specification<Vehicle> stageIn(List<VehicleStage> stages) {
        if (stages == null || stages.isEmpty()) return null;
        return (root, query, cb) -> root.get("stage").in(stages);
    }

    public static Specification<Vehicle> keywordLike(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(cb.coalesce(root.<String>get("vehicleNo"), "")), pattern),
                cb.like(cb.lower(cb.coalesce(root.<String>get("vin"), "")), pattern),
                cb.like(cb.lower(cb.coalesce(root.<String>get("modelName"), "")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("modelYear").as(String.class), "")), pattern),
                cb.like(cb.lower(cb.coalesce(root.get("ownerType").as(String.class), "")), pattern),
                cb.like(cb.lower(cb.coalesce(root.<String>get("shipperName"), "")), pattern)
        );
    }

    public static Specification<Vehicle> shipperNameIs(String shipperName) {
        if (shipperName == null || shipperName.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get("shipperName"), shipperName.trim());
    }

    public static Specification<Vehicle> createdAfter(LocalDate startDate) {
        if (startDate == null) return null;
        Instant start = startDate.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start);
    }

    public static Specification<Vehicle> createdBefore(LocalDate endDate) {
        if (endDate == null) return null;
        Instant end = endDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), end);
    }

    /** 말소 전체출력 이력 여부 필터 */
    public static Specification<Vehicle> malsoPrinted(boolean printed) {
        return (root, query, cb) -> printed
                ? cb.isNotNull(root.get("malsoPrintedAt"))
                : cb.isNull(root.get("malsoPrintedAt"));
    }

    /** 특정 문서 타입이 연결된 차량인지 여부(exists) */
    public static Specification<Vehicle> hasVehicleDocument(DocumentType type) {
        if (type == null) return null;
        return (root, query, cb) -> {
            Subquery<Long> sq = query.subquery(Long.class);
            var doc = sq.from(Document.class);
            sq.select(doc.get("id")).where(
                    cb.equal(doc.get("companyId"), root.get("companyId")),
                    cb.equal(doc.get("refType"), DocumentRefType.VEHICLE),
                    cb.equal(doc.get("refId"), root.get("id")),
                    cb.equal(doc.get("type"), type)
            );
            return cb.exists(sq);
        };
    }

    /** 특정 문서 타입이 없는 차량인지 여부(not exists) */
    public static Specification<Vehicle> noVehicleDocument(DocumentType type) {
        Specification<Vehicle> hasDoc = hasVehicleDocument(type);
        if (hasDoc == null) return null;
        return (root, query, cb) -> cb.not(hasDoc.toPredicate(root, query, cb));
    }
}
