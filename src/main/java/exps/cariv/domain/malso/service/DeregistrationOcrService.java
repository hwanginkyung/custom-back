package exps.cariv.domain.malso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.clova.dto.VehicleDeregistration;
import exps.cariv.domain.clova.service.ClovaResultConverter;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.malso.dto.DeregParseResult;
import exps.cariv.domain.malso.dto.ParsedDereg;
import exps.cariv.domain.malso.entity.Deregistration;
import exps.cariv.domain.malso.repository.DeregistrationRepository;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.global.aws.S3ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 말소증 OCR 처리 서비스.
 *
 * <p>CLOVA OCR + 규칙 파서 + Haiku LLM 보정 파이프라인 사용.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeregistrationOcrService {

    private final exps.cariv.domain.clova.service.DeregistrationOcrService clovaDeregService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final DeregistrationRepository deregRepo;
    private final VehicleRepository vehicleRepo;
    private final NotificationCommandService notificationCommandService;

    @Transactional
    public void processJob(Long companyId, OcrParseJob job) {
        Deregistration doc = deregRepo.findByCompanyIdAndId(companyId, job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException("Deregistration not found id=" + job.getVehicleDocumentId()));

        // 1) S3 -> bytes
        byte[] bytes = s3Reader.readBytes(job.getS3KeySnapshot());
        if (bytes == null) {
            throw new IllegalStateException("S3 object not found: key=" + job.getS3KeySnapshot());
        }

        // 2) CLOVA OCR + 규칙 파서 + Haiku LLM 보정
        VehicleDeregistration clovaResult;
        try {
            clovaResult = clovaDeregService.processBytes(bytes, doc.getOriginalFilename());
        } catch (Exception e) {
            throw new IllegalStateException("CLOVA OCR 처리 실패: " + e.getMessage(), e);
        }

        // 3) newv 결과 → cariv DTO 변환
        ParsedDereg parsedDereg = ClovaResultConverter.toParsedDereg(clovaResult);

        log.info(
                "[Deregistration OCR] jobId={} parsed vehicleNo={} vin={} modelYear={} ownerName={} ownerId={} deRegistrationDate={}",
                job.getId(),
                parsedDereg.registrationNo(),
                parsedDereg.vin(),
                parsedDereg.modelYear(),
                parsedDereg.ownerName(),
                parsedDereg.ownerId(),
                parsedDereg.deRegistrationDate()
        );

        // 4) 누락/에러 필드 체크
        List<String> missingFields = checkMissing(parsedDereg);
        List<String> errorFields = checkErrors(parsedDereg);

        log.info("[Deregistration OCR] missing={} errors={}", missingFields, errorFields);

        // 5) OCR 결과(VIN/차량번호)로 차량 자동 매칭
        Long vehicleId = resolveVehicleId(companyId, parsedDereg);
        vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId)
                .orElseThrow(() -> new IllegalStateException("Vehicle not found id=" + vehicleId));
        job.updateVehicleId(vehicleId);

        // 기존에 해당 차량에 연결된 말소증이 있으면 최신 업로드 문서(doc)로 교체
        deregRepo.findAllByCompanyIdAndRefTypeAndRefIdOrderByCreatedAtDesc(
                        companyId, DocumentRefType.VEHICLE, vehicleId
                ).stream()
                .filter(existing -> !existing.getId().equals(doc.getId()))
                .forEach(existing -> {
                    existing.linkToVehicle(0L);
                    deregRepo.save(existing);
                });
        doc.linkToVehicle(vehicleId);

        // 5-1) OCR 완료 시 자동 stage 전환: BEFORE_DEREGISTRATION → BEFORE_REPORT
        Vehicle matchedVehicle = vehicleRepo.findActiveByIdAndCompanyId(vehicleId, companyId).orElse(null);
        if (matchedVehicle != null && matchedVehicle.getStage() == VehicleStage.BEFORE_DEREGISTRATION) {
            matchedVehicle.updateStage(VehicleStage.BEFORE_REPORT);
            vehicleRepo.save(matchedVehicle);
        }

        // 6) 결과 저장
        doc.applyOcrResult(parsedDereg, null); // CLOVA는 테이블 HTML 없음
        deregRepo.save(doc);

        // 7) Job 성공
        DeregParseResult parsed = new DeregParseResult(parsedDereg, missingFields, errorFields);
        try {
            job.markSucceeded(mapper.writeValueAsString(parsed));
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        // 8) 알림
        String msg = missingFields.isEmpty()
                ? "말소증 OCR 완료"
                : "말소증 OCR 완료 (누락: " + String.join(", ", missingFields) + ")";
        notificationCommandService.createOcr(companyId, job.getRequestedByUserId(),
                NotificationType.OCR_COMPLETE, DocumentType.DEREGISTRATION,
                job.getVehicleId(), job.getId(),
                "말소증 OCR 완료", msg);
    }

    private List<String> checkMissing(ParsedDereg p) {
        List<String> missing = new ArrayList<>();
        if (isBlank(p.registrationNo())) missing.add("차량번호");
        if (p.deRegistrationDate() == null) missing.add("말소등록일");
        return missing;
    }

    private List<String> checkErrors(ParsedDereg p) {
        List<String> errors = new ArrayList<>();
        if (!isBlank(p.registrationNo()) &&
                !p.registrationNo().replaceAll("\\s+", "").matches("\\d{2,3}[가-힣]\\d{4}")) {
            errors.add("registrationNo");
        }
        return errors;
    }

    private Long resolveVehicleId(Long companyId, ParsedDereg parsed) {
        String vin = normalizeVin(parsed.vin());
        String vehicleNo = normalizeVehicleNo(
                firstNonBlank(parsed.registrationNo(), parsed.vehicleNo()));

        List<Vehicle> byVin = vin == null
                ? List.of()
                : vehicleRepo.findAllByCompanyIdAndVinAndDeletedFalseOrderByIdDesc(companyId, vin);
        List<Vehicle> byVehicleNo = vehicleNo == null
                ? List.of()
                : vehicleRepo.findAllByCompanyIdAndVehicleNoAndDeletedFalseOrderByIdDesc(companyId, vehicleNo);

        if (byVin.size() > 1) {
            throw new IllegalStateException(
                    "중복된 차량이 존재합니다: vin=" + vin + " (count=" + byVin.size() + ")"
            );
        }
        if (byVehicleNo.size() > 1) {
            throw new IllegalStateException(
                    "중복된 차량이 존재합니다: vehicleNo=" + vehicleNo + " (count=" + byVehicleNo.size() + ")"
            );
        }

        Vehicle vinMatched = byVin.isEmpty() ? null : byVin.get(0);
        Vehicle vehicleNoMatched = byVehicleNo.isEmpty() ? null : byVehicleNo.get(0);

        if (vinMatched != null && vehicleNoMatched != null
                && !vinMatched.getId().equals(vehicleNoMatched.getId())) {
            throw new IllegalStateException(
                    "OCR 차량 매칭 충돌: vin=" + vin + ", vehicleNo=" + vehicleNo
            );
        }

        if (vinMatched != null) return vinMatched.getId();
        if (vehicleNoMatched != null) return vehicleNoMatched.getId();

        throw new IllegalStateException(
                "OCR 차량 매칭 실패: vin=" + safe(vin) + ", vehicleNo=" + safe(vehicleNo)
        );
    }

    private String normalizeVin(String vin) {
        if (vin == null) return null;
        String normalized = vin.replaceAll("\\s+", "").toUpperCase();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeVehicleNo(String vehicleNo) {
        if (vehicleNo == null) return null;
        String normalized = vehicleNo.replaceAll("\\s+", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String safe(String value) {
        return value == null ? "N/A" : value;
    }
}
