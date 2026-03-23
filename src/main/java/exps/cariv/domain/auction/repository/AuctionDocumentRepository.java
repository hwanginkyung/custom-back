package exps.cariv.domain.auction.repository;

import exps.cariv.domain.auction.entity.AuctionDocument;
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

public interface AuctionDocumentRepository extends JpaRepository<AuctionDocument, Long> {

    Optional<AuctionDocument> findByCompanyIdAndId(Long companyId, Long id);

    Optional<AuctionDocument> findByCompanyIdAndRefTypeAndRefIdAndType(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select d from AuctionDocument d " +
            "where d.companyId = :companyId and d.refType = :refType " +
            "and d.refId = :refId and d.type = :type")
    Optional<AuctionDocument> findForUpdate(
            @Param("companyId") Long companyId,
            @Param("refType") DocumentRefType refType,
            @Param("refId") Long refId,
            @Param("type") DocumentType type
    );
}
