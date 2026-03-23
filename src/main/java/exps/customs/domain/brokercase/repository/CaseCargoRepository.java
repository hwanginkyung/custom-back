package exps.customs.domain.brokercase.repository;

import exps.customs.domain.brokercase.entity.CaseCargo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseCargoRepository extends JpaRepository<CaseCargo, Long> {
    List<CaseCargo> findAllByBrokerCaseId(Long caseId);
}
