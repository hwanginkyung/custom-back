package exps.cariv.domain.shipper.repository;

import exps.cariv.domain.shipper.entity.BizRegDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BizRegDocumentRepository extends JpaRepository<BizRegDocument, Long> {
    Optional<BizRegDocument> findByCompanyIdAndId(Long companyId, Long id);
}
