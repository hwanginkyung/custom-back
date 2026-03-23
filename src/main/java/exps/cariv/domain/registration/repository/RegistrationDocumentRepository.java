package exps.cariv.domain.registration.repository;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RegistrationDocumentRepository extends JpaRepository<RegistrationDocument, Long> {

    Optional<RegistrationDocument> findByCompanyIdAndRefTypeAndRefIdAndType(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type
    );

    /** 업로드 시 row-level 락 확보 (덮어쓰기 방지) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select d from RegistrationDocument d " +
            "where d.companyId = :companyId and d.refType = :refType " +
            "and d.refId = :refId and d.type = :type")
    Optional<RegistrationDocument> findForUpdate(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refId") Long refId,
            @Param("type") DocumentType type
    );

    Optional<RegistrationDocument> findByCompanyIdAndId(Long companyId, Long id);

    void deleteByCompanyIdAndRefTypeAndRefIdAndType(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type
    );
}
