package exps.cariv.domain.malso.repository;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.malso.entity.Deregistration;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DeregistrationRepository extends JpaRepository<Deregistration, Long> {

    /** 배치: 주어진 refId 중 말소증이 존재하는 refId 목록 */
    @Query("SELECT d.refId FROM Deregistration d " +
            "WHERE d.companyId = :companyId AND d.refType = :refType AND d.refId IN :refIds")
    Set<Long> findRefIdsHavingDeregistration(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refIds") Collection<Long> refIds);

    /** 배치: 주어진 refId 목록에 대해 말소등록일(deRegistrationDate)을 조회 */
    @Query("SELECT d.refId, d.deRegistrationDate FROM Deregistration d " +
            "WHERE d.companyId = :companyId AND d.refType = :refType AND d.refId IN :refIds")
    List<Object[]> findDeregDatesByRefIds(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refIds") Collection<Long> refIds);

    Optional<Deregistration> findByCompanyIdAndId(Long companyId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT d FROM Deregistration d WHERE d.companyId = :companyId " +
            "AND d.refType = :refType AND d.refId = :refId AND d.type = :type")
    Optional<Deregistration> findForUpdate(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refId") Long refId,
            @Param("type") DocumentType type
    );

    Optional<Deregistration> findByCompanyIdAndRefTypeAndRefId(
            Long companyId, DocumentRefType refType, Long refId);

    Optional<Deregistration> findTopByCompanyIdAndRefTypeAndRefIdOrderByUploadedAtDescIdDesc(
            Long companyId, DocumentRefType refType, Long refId);

    List<Deregistration> findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
            Long companyId, DocumentRefType refType, Long refId);
}
