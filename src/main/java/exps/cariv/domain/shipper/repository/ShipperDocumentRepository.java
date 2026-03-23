package exps.cariv.domain.shipper.repository;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.shipper.entity.ShipperDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShipperDocumentRepository extends JpaRepository<ShipperDocument, Long> {

    List<ShipperDocument> findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
            Long companyId, DocumentRefType refType, Long refId);

    Optional<ShipperDocument> findByCompanyIdAndRefTypeAndRefIdAndType(
            Long companyId, DocumentRefType refType, Long refId, DocumentType type);

    Optional<ShipperDocument> findByCompanyIdAndId(Long companyId, Long id);
}
