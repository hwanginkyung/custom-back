package exps.cariv.domain.registration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.service.ClovaResultConverter;
import exps.cariv.domain.clova.service.VehicleOcrService;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.registration.dto.RegistrationParseResult;
import exps.cariv.domain.registration.dto.RegistrationParsed;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.domain.vehicle.service.VehicleCommandService;
import exps.cariv.global.aws.S3ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 차량등록증 OCR 처리 서비스.
 *
 * <p>CLOVA OCR + 규칙 파서 + Haiku LLM 보정 파이프라인 사용.
 * <p>성공 시: 문서 파싱 결과 저장 + Vehicle 반영 + Job 성공 + 알림 생성.
 * <p>실패 시: 예외를 OcrJobProcessor로 전파하여 별도 트랜잭션에서 실패 처리.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationOcrService {

    private final VehicleOcrService vehicleOcrService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;

    private final RegistrationDocumentRepository regDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final VehicleCommandService vehicleCommandService;
    private final NotificationCommandService notificationCommandService;
    private final PlatformTransactionManager txManager;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processJob(Long companyId, OcrParseJob job) {
        RegistrationDocument doc = regDocRepo.findByCompanyIdAndId(companyId, job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException(
                        "RegistrationDocument not found id=" + job.getVehicleDocumentId()));

        // 1) S3 -> bytes
        byte[] bytes = s3Reader.readBytes(job.getS3KeySnapshot());
        if (bytes == null) {
            throw new IllegalStateException("S3 object not found: key=" + job.getS3KeySnapshot());
        }

        // 2) CLOVA OCR + 규칙 파서 + Haiku LLM 보정
        VehicleRegistration clovaResult;
        try {
            clovaResult = vehicleOcrService.processBytes(bytes, doc.getOriginalFilename());
        } catch (Exception e) {
            throw new IllegalStateException("CLOVA OCR 처리 실패: " + e.getMessage(), e);
        }

        // 3) newv 결과 → cariv DTO 변환
        RegistrationParsed p = ClovaResultConverter.toRegistrationParsed(clovaResult);

        // 4) 누락/에러 필드 체크
        List<String> missingFields = checkMissing(p);
        List<String> errorFields = checkErrors(p);
        RegistrationParseResult parsed = new RegistrationParseResult(p, missingFields, errorFields);

        // 5) 결과 직렬화
        String resultJson;
        try {
            resultJson = mapper.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        String msg = missingFields.isEmpty()
                ? "차량등록증 OCR 완료"
                : "차량등록증 OCR 완료 (누락: " + String.join(", ", missingFields) + ")";

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> persistSuccess(
                companyId,
                job.getId(),
                job.getVehicleDocumentId(),
                job.getRequestedByUserId(),
                p,
                null, // tableHtmlJson: CLOVA는 테이블 HTML 없음
                resultJson,
                msg
        ));
    }

    private List<String> checkMissing(RegistrationParsed p) {
        List<String> missing = new ArrayList<>();
        if (isBlank(p.vin())) missing.add("vin");
        if (isBlank(p.vehicleNo())) missing.add("vehicleNo");
        if (isBlank(p.carType())) missing.add("carType");
        if (isBlank(p.vehicleUse())) missing.add("vehicleUse");
        if (isBlank(p.modelName())) missing.add("modelName");
        if (isBlank(p.engineType())) missing.add("engineType");
        if (isBlank(p.ownerName())) missing.add("ownerName");
        if (isBlank(p.ownerId())) missing.add("ownerId");
        // 추가 필드 누락 체크
        if (p.modelYear() == null) missing.add("modelYear");
        if (p.firstRegistratedAt() == null) missing.add("firstRegistratedAt");
        if (isBlank(p.manufactureYearMonth())) missing.add("manufactureYearMonth");
        if (isBlank(p.fuelType())) missing.add("fuelType");
        if (p.displacement() == null) missing.add("displacement");
        if (p.mileageKm() == null) missing.add("mileageKm");
        if (p.seating() == null) missing.add("seating");
        if (p.weightKg() == null) missing.add("weightKg");
        if (p.lengthMm() == null) missing.add("lengthMm");
        if (p.heightMm() == null) missing.add("heightMm");
        if (p.widthMm() == null) missing.add("widthMm");
        return missing;
    }

    private List<String> checkErrors(RegistrationParsed p) {
        List<String> errors = new ArrayList<>();

        // 차량번호: 12가1234 or 123가1234
        if (!isBlank(p.vehicleNo()) &&
                !p.vehicleNo().replaceAll("\\s+", "").matches("\\d{2,3}[가-힣]\\d{4}")) {
            errors.add("vehicleNo");
        }
        // 차대번호: 영숫자 17자리 (I, O, Q 제외)
        if (!isBlank(p.vin()) &&
                !p.vin().replaceAll("\\s+", "").toUpperCase().matches("[A-HJ-NPR-Z0-9]{17}")) {
            errors.add("vin");
        }
        // 주민번호/법인번호: 000000-0000000
        if (!isBlank(p.ownerId()) &&
                !p.ownerId().matches("\\d{6}-\\d{7}")) {
            errors.add("ownerId");
        }
        // 연식: 1900~현재+1년
        if (p.modelYear() != null &&
                (p.modelYear() < 1900 || p.modelYear() > LocalDate.now().getYear() + 1)) {
            errors.add("modelYear");
        }
        // 최초등록일: 미래 날짜 불가, 1950년 이전 불가
        if (p.firstRegistratedAt() != null &&
                (p.firstRegistratedAt().isAfter(LocalDate.now()) ||
                 p.firstRegistratedAt().isBefore(LocalDate.of(1950, 1, 1)))) {
            errors.add("firstRegistratedAt");
        }
        // 제작연월: yyyy-MM 형식
        if (!isBlank(p.manufactureYearMonth()) &&
                !p.manufactureYearMonth().matches("\\d{4}-\\d{2}")) {
            errors.add("manufactureYearMonth");
        }
        // 배기량: 50~15000cc
        if (p.displacement() != null &&
                (p.displacement() < 50 || p.displacement() > 15000)) {
            errors.add("displacement");
        }
        // 주행거리: 0 이상, 100만km 이하
        if (p.mileageKm() != null &&
                (p.mileageKm() < 0 || p.mileageKm() > 1_000_000)) {
            errors.add("mileageKm");
        }
        // 승차정원: 1~99
        if (p.seating() != null &&
                (p.seating() < 1 || p.seating() > 99)) {
            errors.add("seating");
        }
        // 중량: 100~50000kg
        if (p.weightKg() != null &&
                (p.weightKg() < 100 || p.weightKg() > 50000)) {
            errors.add("weightKg");
        }
        // 길이: 1000~30000mm (1m~30m)
        if (p.lengthMm() != null &&
                (p.lengthMm() < 1000 || p.lengthMm() > 30000)) {
            errors.add("lengthMm");
        }
        // 높이: 500~5000mm (0.5m~5m)
        if (p.heightMm() != null &&
                (p.heightMm() < 500 || p.heightMm() > 5000)) {
            errors.add("heightMm");
        }
        // 너비: 500~3000mm (0.5m~3m)
        if (p.widthMm() != null &&
                (p.widthMm() < 500 || p.widthMm() > 3000)) {
            errors.add("widthMm");
        }
        return errors;
    }

    private void persistSuccess(Long companyId,
                                Long jobId,
                                Long vehicleDocumentId,
                                Long requestedByUserId,
                                RegistrationParsed parsed,
                                String tableHtmlJson,
                                String resultJson,
                                String notificationMessage) {
        RegistrationDocument doc = regDocRepo.findByCompanyIdAndId(companyId, vehicleDocumentId)
                .orElseThrow(() -> new IllegalStateException("RegistrationDocument not found id=" + vehicleDocumentId));

        doc.applyOcrResult(parsed, tableHtmlJson);
        regDocRepo.save(doc);

        OcrParseJob persistedJob = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new IllegalStateException("OcrParseJob not found id=" + jobId));

        Long linkedVehicleId = resolveLinkedVehicleId(doc, persistedJob);
        if (linkedVehicleId != null && linkedVehicleId > 0) {
            try {
                vehicleCommandService.applyRegistration(companyId, linkedVehicleId, parsed);
            } catch (Exception ex) {
                log.warn("[Registration OCR] vehicle apply skipped. jobId={}, docId={}, vehicleId={}, reason={}",
                        jobId, vehicleDocumentId, linkedVehicleId, ex.getMessage());
            }
        } else {
            log.info("[Registration OCR] vehicle not linked yet, skip vehicle apply. jobId={}, docId={}",
                    jobId, vehicleDocumentId);
        }

        persistedJob.markSucceeded(resultJson);
        jobRepo.save(persistedJob);

        notificationCommandService.createOcr(companyId, requestedByUserId,
                NotificationType.OCR_COMPLETE,
                DocumentType.REGISTRATION,
                linkedVehicleId,
                jobId,
                "등록증 OCR 완료",
                notificationMessage);
    }

    private Long resolveLinkedVehicleId(RegistrationDocument doc, OcrParseJob job) {
        if (doc.getRefId() != null && doc.getRefId() > 0) {
            return doc.getRefId();
        }
        if (job.getVehicleId() != null && job.getVehicleId() > 0) {
            return job.getVehicleId();
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
