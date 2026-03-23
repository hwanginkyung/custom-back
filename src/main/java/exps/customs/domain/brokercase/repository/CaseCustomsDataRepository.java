package exps.customs.domain.brokercase.repository;

import exps.customs.domain.brokercase.entity.CaseCustomsData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CaseCustomsDataRepository extends JpaRepository<CaseCustomsData, Long> {
    Optional<CaseCustomsData> findByBrokerCaseId(Long caseId);
}
