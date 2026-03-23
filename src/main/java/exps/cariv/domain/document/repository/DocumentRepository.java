package exps.cariv.domain.document.repository;

import exps.cariv.domain.document.entity.*;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByCompanyIdAndRefTypeAndRefIdAndType(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type
    );

    Optional<Document> findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select d from Document d " +
            "where d.companyId = :companyId and d.refType = :refType and d.refId = :refId and d.type = :type")
    Optional<Document> findForUpdate(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refId") Long refId,
            @Param("type") DocumentType type
    );

    List<Document> findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
            Long companyId, DocumentRefType refType, Long refId
    );

    List<Document> findAllByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type
    );

    /**
     * 특정 문서 타입이 존재하는 refId 목록을 일괄 조회 (N+1 방지).
     * <p>사용 예: 수출신고필증 보유 차량 ID 일괄 조회</p>
     */
    @Query("SELECT DISTINCT d.refId FROM Document d " +
           "WHERE d.companyId = :companyId AND d.refType = :refType " +
           "AND d.refId IN :refIds AND d.type = :type")
    Set<Long> findRefIdsHavingType(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refIds") Collection<Long> refIds,
            @Param("type") DocumentType type
    );

    /**
     * 여러 refId에 대한 문서를 한 번에 조회 (N+1 방지).
     */
    @Query("SELECT d FROM Document d " +
           "WHERE d.companyId = :companyId AND d.refType = :refType " +
           "AND d.refId IN :refIds ORDER BY d.createdAt DESC")
    List<Document> findAllByCompanyIdAndRefTypeAndRefIdIn(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refIds") Collection<Long> refIds
    );

    /**
     * 단일 refId의 문서 중, 대상 타입에 해당하는 실제 존재 타입만 일괄 조회.
     */
    @Query("SELECT DISTINCT d.type FROM Document d " +
           "WHERE d.companyId = :companyId AND d.refType = :refType " +
           "AND d.refId = :refId AND d.type IN :types")
    Set<DocumentType> findExistingTypes(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refId") Long refId,
            @Param("types") Collection<DocumentType> types
    );
}
