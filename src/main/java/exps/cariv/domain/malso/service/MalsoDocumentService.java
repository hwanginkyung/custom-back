package exps.cariv.domain.malso.service;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.malso.repository.DeregistrationRepository;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.aws.S3ObjectReader;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MalsoDocumentService {

    private final DocumentRepository documentRepo;
    private final VehicleRepository vehicleRepo;
    private final DeregistrationRepository deregRepo;
    private final S3ObjectReader s3Reader;

    /**
     * 차량에 연결된 모든 문서를 ZIP으로 묶어 반환.
     */
    @Transactional(readOnly = true)
    public byte[] buildZip(Long companyId, Long vehicleId) {
        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        List<Document> docs = documentRepo.findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                companyId, DocumentRefType.VEHICLE, vehicleId);
        docs = ensureDeregistrationIncluded(companyId, vehicle, docs);

        if (docs.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND);
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            int idx = 1;
            for (Document doc : docs) {
                byte[] bytes = s3Reader.readBytes(doc.getS3Key());
                if (bytes == null) continue;

                String filename = doc.getOriginalFilename() != null
                        ? doc.getOriginalFilename()
                        : doc.getType().name() + "_" + idx;

                // 중복 방지
                String entryName = idx + "_" + filename;
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(bytes);
                zos.closeEntry();
                idx++;
            }

            zos.finish();
            return baos.toByteArray();

        } catch (IOException e) {
            log.error("[MalsoDocumentService] ZIP build failed vehicleId={}", vehicleId, e);
            throw new IllegalStateException("ZIP 파일 생성 실패", e);
        }
    }

    /**
     * 인쇄용 문서 목록 반환 (각 문서의 바이트 + 메타정보).
     */
    @Transactional(readOnly = true)
    public List<PrintDocument> buildPrintDocuments(Long companyId, Long vehicleId) {
        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        List<Document> docs = documentRepo.findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                companyId, DocumentRefType.VEHICLE, vehicleId);
        docs = ensureDeregistrationIncluded(companyId, vehicle, docs);

        return docs.stream()
                .map(doc -> {
                    byte[] bytes = s3Reader.readBytes(doc.getS3Key());
                    return new PrintDocument(
                            doc.getId(),
                            doc.getType().name(),
                            doc.getOriginalFilename(),
                            doc.getContentType(),
                            bytes
                    );
                })
                .filter(pd -> pd.data() != null)
                .toList();
    }

    private List<Document> ensureDeregistrationIncluded(Long companyId, Vehicle vehicle, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            docs = new java.util.ArrayList<>();
        } else {
            docs = new java.util.ArrayList<>(docs);
        }

        boolean hasDereg = docs.stream().anyMatch(d -> d.getType() == DocumentType.DEREGISTRATION);
        if (!hasDereg && isDoneVehicle(vehicle)) {
            deregRepo.findTopByCompanyIdAndRefTypeAndRefIdOrderByUploadedAtDescIdDesc(
                    companyId, DocumentRefType.VEHICLE, vehicle.getId()
            ).ifPresent(docs::add);
        }
        return docs;
    }

    private boolean isDoneVehicle(Vehicle vehicle) {
        return vehicle.getStage() == VehicleStage.BEFORE_REPORT
                || vehicle.getStage() == VehicleStage.BEFORE_CERTIFICATE
                || vehicle.getStage() == VehicleStage.COMPLETED;
    }

    public record PrintDocument(
            Long documentId,
            String type,
            String originalFilename,
            String contentType,
            byte[] data
    ) {}
}
