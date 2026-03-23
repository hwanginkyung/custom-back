package exps.cariv.domain.ocr.service;

import exps.cariv.domain.malso.service.DeregistrationOcrService;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.registration.service.RegistrationOcrService;
import exps.cariv.domain.shipper.service.BizRegOcrService;
import exps.cariv.domain.shipper.service.IdCardOcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OCR 작업 실제 처리 로직.
 * OcrJobWorker에서 분리하여 Spring 프록시를 통한
 * {@code @Transactional(REQUIRES_NEW)} 가 정상 동작하도록 함.
 *
 * <p>성공 시: processJob 내부에서 문서/Vehicle/Job 상태 모두 커밋.
 * <p>실패 시: 별도 REQUIRES_NEW 트랜잭션으로 Job 실패 상태 + 알림 저장.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrJobProcessor {

    private static final int JOB_ERROR_MAX_LEN = 240;

    private final OcrParseJobRepository jobRepo;
    private final NotificationCommandService notificationCommandService;
    private final RegistrationOcrService registrationOcrService;
    private final DeregistrationOcrService deregistrationOcrService;
    private final BizRegOcrService bizRegOcrService;
    private final IdCardOcrService idCardOcrService;

    /**
     * 성공 경로: 문서 타입별 processJob에서 성공 상태를 반영한다.
     * 실패 시 예외가 전파되어 이 트랜잭션은 롤백되고,
     * {@link #handleFailure}에서 별도 트랜잭션으로 실패 상태를 기록한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long jobId) {
        OcrParseJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("job not found id=" + jobId));

        switch (job.getDocumentType()) {
            case REGISTRATION -> registrationOcrService.processJob(job.getCompanyId(), job);
            case DEREGISTRATION -> deregistrationOcrService.processJob(job.getCompanyId(), job);
            case BIZ_REGISTRATION -> bizRegOcrService.processJob(job.getCompanyId(), job);
            case ID_CARD -> idCardOcrService.processJob(job.getCompanyId(), job);
            default -> throw new IllegalStateException("Unsupported documentType=" + job.getDocumentType());
        }

        // 여기 도달하면 성공 — job 상태는 각 processJob 내부에서 처리됨
    }

    /**
     * 실패 처리 전용 트랜잭션.
     * process()가 예외로 롤백된 뒤 OcrJobWorker가 이 메서드를 호출하여
     * 별도 트랜잭션에서 실패 상태와 알림을 저장한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailure(Long jobId, Exception error) {
        OcrParseJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[OcrJobProcessor] job not found for failure handling id={}", jobId);
            return;
        }

        String errorMsg = toCompactErrorMessage(error);
        String persistedError = truncate("오류: " + errorMsg, 255);
        job.markFailed(persistedError);
        jobRepo.save(job);

        // 실패 알림 생성
        try {
            notificationCommandService.createOcr(
                    job.getCompanyId(),
                    job.getRequestedByUserId(),
                    NotificationType.OCR_FAILED,
                    job.getDocumentType(),
                    job.getVehicleId(),
                    job.getId(),
                    "OCR 처리 실패",
                    truncate("오류: " + errorMsg, JOB_ERROR_MAX_LEN)
            );
        } catch (Exception notifEx) {
            log.error("[OcrJobProcessor] failed to create failure notification jobId={}", jobId, notifEx);
        }
    }

    private String toCompactErrorMessage(Throwable error) {
        if (error == null) {
            return "알 수 없는 오류";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}
