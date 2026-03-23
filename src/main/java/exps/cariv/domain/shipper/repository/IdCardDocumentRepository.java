package exps.cariv.domain.shipper.repository;

import exps.cariv.domain.shipper.entity.IdCardDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdCardDocumentRepository extends JpaRepository<IdCardDocument, Long> {
    Optional<IdCardDocument> findByCompanyIdAndId(Long companyId, Long id);
}
