package exps.cariv.domain.document.service;

import exps.cariv.domain.document.entity.*;
import exps.cariv.domain.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DocumentCommandService {

    private final DocumentRepository repo;

    @Transactional
    @SuppressWarnings("unchecked")
    public <T extends Document> T upsertAndReplace(
            Long companyId,
            DocumentRefType refType,
            Long refId,
            DocumentType type,
            Long uploadedByUserId,
            String s3Key,
            String originalFilename,
            String contentType,
            Long sizeBytes,
            Supplier<T> creator
    ) {
        Optional<Document> locked = repo.findForUpdate(companyId, refType, refId, type);

        Document doc = locked.orElseGet(() -> {
            T created = creator.get();
            created.setCompanyId(companyId);
            created.init(type, refType, refId, uploadedByUserId, s3Key, originalFilename, contentType, sizeBytes);
            return created;
        });

        if (locked.isPresent()) {
            doc.replaceFile(uploadedByUserId, s3Key, originalFilename, contentType, sizeBytes);
        }

        return (T) repo.save(doc);
    }
}
