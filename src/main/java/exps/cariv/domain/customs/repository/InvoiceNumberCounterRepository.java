package exps.cariv.domain.customs.repository;

import exps.cariv.domain.customs.entity.InvoiceNumberCounter;
import exps.cariv.domain.customs.entity.InvoiceNumberType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface InvoiceNumberCounterRepository extends JpaRepository<InvoiceNumberCounter, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c
            FROM InvoiceNumberCounter c
            WHERE c.companyId = :companyId
              AND c.customsBrokerId = :customsBrokerId
              AND c.invoiceType = :invoiceType
              AND c.businessDate = :businessDate
            """)
    Optional<InvoiceNumberCounter> findForUpdate(@Param("companyId") Long companyId,
                                                 @Param("customsBrokerId") Long customsBrokerId,
                                                 @Param("invoiceType") InvoiceNumberType invoiceType,
                                                 @Param("businessDate") LocalDate businessDate);
}
