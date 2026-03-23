package exps.cariv.domain.export.repository;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.export.entity.ExportCertificateDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExportCertificateDocumentRepository extends JpaRepository<ExportCertificateDocument, Long> {

    Optional<ExportCertificateDocument> findByCompanyIdAndId(Long companyId, Long id);

    Optional<ExportCertificateDocument> findFirstByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
            Long companyId, DocumentRefType refType, Long refId);

    List<ExportCertificateDocument> findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
            Long companyId, DocumentRefType refType, Long refId
    );
}
