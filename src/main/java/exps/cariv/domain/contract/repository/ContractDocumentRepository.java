package exps.cariv.domain.contract.repository;

import exps.cariv.domain.contract.entity.ContractDocument;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContractDocumentRepository extends JpaRepository<ContractDocument, Long> {

    Optional<ContractDocument> findByCompanyIdAndId(Long companyId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select d from ContractDocument d " +
            "where d.companyId = :companyId and d.refType = :refType " +
            "and d.refId = :refId and d.type = :type")
    Optional<ContractDocument> findForUpdate(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refId") Long refId,
            @Param("type") DocumentType type
    );
}
