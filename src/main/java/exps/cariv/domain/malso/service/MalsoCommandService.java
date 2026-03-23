package exps.cariv.domain.malso.service;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.malso.dto.request.MalsoCompleteUpdateRequest;
import exps.cariv.domain.malso.dto.response.MalsoUploadResponse;
import exps.cariv.domain.malso.entity.Deregistration;
import exps.cariv.domain.malso.repository.DeregistrationRepository;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrQueueService;
import exps.cariv.domain.vehicle.dto.VehiclePatch;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.aws.S3Upload.UploadResult;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MalsoCommandService {

    private final DeregistrationRepository deregRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrQueueService ocrQueueService;
    private final S3Upload s3Upload;
    private final VehicleRepository vehicleRepo;

    /**
     * 말소증 업로드 → OCR 대기열.
     */
    @Transactional
    public MalsoUploadResponse uploadDeregistration(Long companyId, Long userId,
                                                     MultipartFile file) {
        // 말소 업로드는 vehicleId를 미리 받지 않고, OCR 결과(VIN/차량번호)로 차량을 매칭한다.
        Deregistration doc = Deregistration.createNew(
                companyId, userId, 0L, "__PENDING__", file.getOriginalFilename());
        doc = deregRepo.saveAndFlush(doc);

        // S3 업로드
        UploadResult up = s3Upload.uploadRawDocument(companyId, doc.getId(), file);
        doc.replaceFile(userId, up.s3Key(), up.originalFilename(), up.contentType(), up.sizeBytes());
        doc = deregRepo.save(doc);

        // OCR Job 생성 + 큐 enqueue
        OcrParseJob job = OcrParseJob.builder()
                .documentType(DocumentType.DEREGISTRATION)
                .status(OcrJobStatus.QUEUED)
                .vehicleId(0L)
                .vehicleDocumentId(doc.getId())
                .requestedByUserId(userId)
                .s3KeySnapshot(doc.getS3Key())
                .build();
        job.setCompanyId(companyId);
        job = jobRepo.save(job);

        enqueueAfterCommit(job.getId());

        return new MalsoUploadResponse(doc.getId(), up.s3Key(), job.getId());
    }

    /**
     * 말소 완료 결과 수동 수정.
     */
    @Transactional
    public void updateComplete(Long companyId, Long vehicleId, MalsoCompleteUpdateRequest req) {
        Deregistration doc = deregRepo.findTopByCompanyIdAndRefTypeAndRefIdOrderByUploadedAtDescIdDesc(
                        companyId, DocumentRefType.VEHICLE, vehicleId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        doc.manualUpdate(
                firstNonBlank(req.registrationNo(), req.vehicleNo()),
                req.vin(),
                req.modelName(),
                req.modelYear(),
                req.ownerName(),
                req.ownerId(),
                req.documentNo(),
                req.specNo(),
                req.deRegistrationDate(), req.deRegistrationReason(),
                req.rightsRelation()
        );
        doc.markConfirmed();
        deregRepo.save(doc);

        Vehicle vehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        VehiclePatch patch = new VehiclePatch(
                firstNonBlank(req.vin(), doc.getVin()),
                firstNonBlank(req.vehicleNo(), req.registrationNo(), doc.getRegistrationNo()),
                null,
                null,
                firstNonBlank(req.modelName(), doc.getModelName()),
                null,
                null,
                firstNonBlank(req.ownerName(), doc.getOwnerName()),
                firstNonBlank(req.ownerId(), doc.getOwnerId()),
                firstNonNull(req.modelYear(), doc.getModelYear()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        vehicle.applyPatch(patch);
        if (req.deRegistrationDate() != null) {
            vehicle.updateDeRegistrationDate(req.deRegistrationDate());
        } else if (doc.getDeRegistrationDate() != null) {
            vehicle.updateDeRegistrationDate(doc.getDeRegistrationDate());
        }
        if (vehicle.getStage() == VehicleStage.BEFORE_DEREGISTRATION) {
            vehicle.updateStage(VehicleStage.BEFORE_REPORT);
        }
        vehicleRepo.save(vehicle);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        if (c != null && !c.isBlank()) return c;
        return null;
    }

    private Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }

    private void enqueueAfterCommit(Long jobId) {
        if (jobId == null || jobId <= 0) return;

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            ocrQueueService.enqueue(jobId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ocrQueueService.enqueue(jobId);
            }
        });
    }
}
