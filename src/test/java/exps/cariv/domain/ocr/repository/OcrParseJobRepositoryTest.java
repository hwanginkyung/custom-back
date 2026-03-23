package exps.cariv.domain.ocr.repository;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OcrParseJobRepositoryTest {

    @Autowired
    private OcrParseJobRepository repository;

    @Test
    void findByCompanyIdAndIdReturnsOnlyMatchingTenantData() {
        OcrParseJob company1Job = repository.save(newJob(1L, 101L, DocumentType.REGISTRATION, OcrJobStatus.QUEUED));
        OcrParseJob company2Job = repository.save(newJob(2L, 101L, DocumentType.REGISTRATION, OcrJobStatus.QUEUED));

        Optional<OcrParseJob> foundForCompany1 = repository.findByCompanyIdAndId(1L, company1Job.getId());
        Optional<OcrParseJob> notFoundForCompany1 = repository.findByCompanyIdAndId(1L, company2Job.getId());

        assertThat(foundForCompany1).isPresent();
        assertThat(foundForCompany1.get().getCompanyId()).isEqualTo(1L);
        assertThat(notFoundForCompany1).isEmpty();
    }

    @Test
    void findByCompanyIdAndVehicleDocumentIdAndDocumentTypeIsTenantIsolated() {
        repository.save(newJob(1L, 555L, DocumentType.AUCTION_CERTIFICATE, OcrJobStatus.QUEUED));
        repository.save(newJob(2L, 555L, DocumentType.AUCTION_CERTIFICATE, OcrJobStatus.QUEUED));
        repository.save(newJob(1L, 555L, DocumentType.CONTRACT, OcrJobStatus.QUEUED));

        List<OcrParseJob> result = repository.findByCompanyIdAndVehicleDocumentIdAndDocumentType(
                1L, 555L, DocumentType.AUCTION_CERTIFICATE
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCompanyId()).isEqualTo(1L);
        assertThat(result.get(0).getDocumentType()).isEqualTo(DocumentType.AUCTION_CERTIFICATE);
    }

    @Test
    void findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDescHonorsTenantAndStatus() throws Exception {
        repository.save(newJob(1L, 888L, DocumentType.REGISTRATION, OcrJobStatus.FAILED));
        repository.save(newJob(2L, 888L, DocumentType.REGISTRATION, OcrJobStatus.SUCCEEDED));
        Thread.sleep(5L);
        OcrParseJob expected = repository.save(newJob(1L, 888L, DocumentType.REGISTRATION, OcrJobStatus.SUCCEEDED));

        Optional<OcrParseJob> result = repository.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                1L, 888L, OcrJobStatus.SUCCEEDED
        );

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(expected.getId());
        assertThat(result.get().getCompanyId()).isEqualTo(1L);
    }

    private OcrParseJob newJob(Long companyId, Long documentId, DocumentType type, OcrJobStatus status) {
        OcrParseJob job = OcrParseJob.builder()
                .documentType(type)
                .status(status)
                .vehicleId(0L)
                .vehicleDocumentId(documentId)
                .requestedByUserId(10L)
                .s3KeySnapshot("s3://test/" + documentId)
                .build();
        job.setCompanyId(companyId);
        return job;
    }
}
