package exps.customs.domain.brokercase.repository;

import exps.customs.domain.brokercase.entity.BrokerCase;
import exps.customs.domain.brokercase.entity.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BrokerCaseRepository extends JpaRepository<BrokerCase, Long> {
    Optional<BrokerCase> findByCaseNumber(String caseNumber);
    List<BrokerCase> findAllByCompanyIdOrderByCreatedAtDesc(Long companyId);
    List<BrokerCase> findAllByCompanyIdAndStatus(Long companyId, CaseStatus status);
    List<BrokerCase> findAllByCompanyIdAndClientId(Long companyId, Long clientId);

    @Query("SELECT COUNT(c) FROM BrokerCase c WHERE c.companyId = :companyId AND c.status = :status")
    long countByCompanyIdAndStatus(@Param("companyId") Long companyId, @Param("status") CaseStatus status);

    @Query("SELECT COUNT(c) FROM BrokerCase c WHERE c.companyId = :companyId")
    long countByCompanyId(@Param("companyId") Long companyId);

    @Query("SELECT c FROM BrokerCase c WHERE c.companyId = :companyId AND c.etaDate BETWEEN :from AND :to ORDER BY c.etaDate ASC")
    List<BrokerCase> findByCompanyIdAndEtaDateBetween(@Param("companyId") Long companyId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT c FROM BrokerCase c WHERE c.companyId = :companyId AND c.status IN :statuses ORDER BY c.etaDate ASC")
    List<BrokerCase> findByCompanyIdAndStatusIn(@Param("companyId") Long companyId, @Param("statuses") List<CaseStatus> statuses);
}
