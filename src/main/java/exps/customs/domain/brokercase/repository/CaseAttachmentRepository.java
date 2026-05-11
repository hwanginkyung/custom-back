package exps.customs.domain.brokercase.repository;

import exps.customs.domain.brokercase.entity.CaseAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaseAttachmentRepository extends JpaRepository<CaseAttachment, Long> {
    List<CaseAttachment> findAllByBrokerCaseId(Long caseId);
    Optional<CaseAttachment> findByIdAndBrokerCaseId(Long id, Long brokerCaseId);
}
