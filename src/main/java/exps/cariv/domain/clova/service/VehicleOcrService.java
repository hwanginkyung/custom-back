package exps.cariv.domain.clova.service;

import exps.cariv.domain.clova.client.OcrClient;
import exps.cariv.domain.clova.dto.FieldEvidence;
import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.layout.LayoutNormalizer;
import exps.cariv.domain.clova.layout.NormalizedLayout;
import exps.cariv.domain.clova.parser.DocumentParser;
import exps.cariv.domain.clova.parser.DocumentType;
import exps.cariv.domain.clova.parser.DocumentTypeClassifier;
import exps.cariv.domain.clova.parser.DocumentTypeResult;
import exps.cariv.domain.clova.preprocess.DocumentImagePreprocessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleOcrService {

    private final OcrClient ocrClient;
    private final DocumentImagePreprocessor imagePreprocessor;
    private final LayoutNormalizer layoutNormalizer;
    private final DocumentTypeClassifier documentTypeClassifier;
    private final List<DocumentParser> parsers;
    @Autowired(required = false)
    private RegistrationLlmRefiner registrationLlmRefiner;

    /**
     * 이미지 파일 경로로 자동차등록증 OCR 처리
     */
    public VehicleRegistration processFile(Path imagePath) throws IOException {
        log.info("Processing vehicle registration: {}", imagePath);
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String fileName = imagePath.getFileName() != null ? imagePath.getFileName().toString() : "image.jpg";
        return processBytes(imageBytes, fileName);
    }

    /**
     * 업로드된 이미지 바이트로 자동차등록증 OCR 처리
     */
    public VehicleRegistration processBytes(byte[] imageBytes, String fileName) throws IOException {
        log.info("Processing vehicle registration from upload: {}", fileName);

        List<AttemptResult> attempts = new ArrayList<>();
        AttemptResult base = runAttempt(imageBytes, fileName, 0, false);
        attempts.add(base);

        if (shouldTryPreprocessFallback(base)) {
            byte[] preprocessed = imagePreprocessor.preprocess(imageBytes, fileName);
            AttemptResult preprocessedBase = runAttempt(preprocessed, fileName, 0, true);
            attempts.add(preprocessedBase);

            if (shouldTryRotation(preprocessedBase)) {
                attempts.add(runAttempt(imagePreprocessor.rotate(preprocessed, 90), fileName, 90, true));
                attempts.add(runAttempt(imagePreprocessor.rotate(preprocessed, 270), fileName, 270, true));
            }
        }

        AttemptResult best = pickBest(attempts);

        VehicleRegistration finalResult = best.result;

        if (best.preprocessedInput && best.rotation == 0) {
            appendQualityReason(finalResult, "전처리 OCR 적용");
        } else if (best.rotation != 0) {
            appendQualityReason(finalResult, "전처리 회전보정 적용(" + best.rotation + "도)");
        }
        if (registrationLlmRefiner != null) {
            finalResult = registrationLlmRefiner.refineIfNeeded(
                    finalResult,
                    best.words,
                    ocrClient.provider(),
                    fileName
            );
        }

        log.info(
                "OCR provider={} attempts={} selectedPreprocessed={} selectedRotation={} selectedScore={} needsRetry={}",
                ocrClient.provider(),
                attempts.size(),
                best.preprocessedInput,
                best.rotation,
                round4(best.rank),
                finalResult.getNeedsRetry()
        );
        return finalResult;
    }

    private AttemptResult runAttempt(byte[] bytes, String fileName, int rotation, boolean preprocessedInput) throws IOException {
        String attemptName = buildAttemptFileName(fileName, rotation);
        List<OcrWord> words = ocrClient.recognize(bytes, attemptName);
        log.info("OCR returned {} words (preprocessed={}, rotation={}°)", words.size(), preprocessedInput, rotation);

        VehicleRegistration result = parseWithPipeline(words);
        boolean orientationSuspicious = isOrientationSuspicious(words);
        double rank = scoreAttempt(result, orientationSuspicious, rotation);
        return new AttemptResult(rotation, preprocessedInput, words, result, orientationSuspicious, rank);
    }

    private boolean shouldTryPreprocessFallback(AttemptResult base) {
        if (base == null || base.result == null) return true;
        VehicleRegistration r = base.result;

        if (Boolean.TRUE.equals(r.getNeedsRetry())) return true;
        if (Boolean.FALSE.equals(r.getQualityGatePassed())) return true;
        if (base.orientationSuspicious) return true;

        if (isBlank(r.getVin()) || isBlank(r.getVehicleNo())) return true;
        if (isBlank(r.getFirstRegistratedAt()) || isBlank(r.getManufactureYearMonth())) return true;

        return fieldConfidence(r, "vin") < 0.90 || fieldConfidence(r, "vehicleNo") < 0.85;
    }

    private boolean shouldTryRotation(AttemptResult base) {
        if (base.orientationSuspicious) return true;

        // 빈 필드가 5개 이상일 때만 회전 재시도
        int blankCount = 0;
        VehicleRegistration r = base.result;
        if (isBlank(r.getVin())) blankCount++;
        if (isBlank(r.getVehicleNo())) blankCount++;
        if (isBlank(r.getFirstRegistratedAt())) blankCount++;
        if (isBlank(r.getManufactureYearMonth())) blankCount++;
        if (isBlank(r.getOwnerName())) blankCount++;
        if (isBlank(r.getOwnerId())) blankCount++;
        if (isBlank(r.getEngineType())) blankCount++;
        if (isBlank(r.getModelName())) blankCount++;
        if (isBlank(r.getWeight())) blankCount++;
        if (isBlank(r.getMaxLoad())) blankCount++;
        return blankCount >= 5;
    }

    private AttemptResult pickBest(List<AttemptResult> attempts) {
        return attempts.stream()
                .max((a, b) -> Double.compare(a.rank, b.rank))
                .orElseThrow();
    }

    private double scoreAttempt(VehicleRegistration result, boolean orientationSuspicious, int rotation) {
        double score = 0.0;
        // needsRetry 보너스를 줄여서, 실제 필드 품질이 더 중요하게 반영되도록
        if (!Boolean.TRUE.equals(result.getNeedsRetry())) score += 200;

        score += nz(result.getQualityScore()) * 120;
        score += nz(result.getDocumentTypeScore()) * 20;
        score += fieldConfidence(result, "vin") * 80;
        score += fieldConfidence(result, "vehicleNo") * 60;
        score += fieldConfidence(result, "manufactureYearMonth") * 50;
        score += fieldConfidence(result, "modelYear") * 30;
        score += fieldConfidence(result, "firstRegistratedAt") * 30;

        // 핵심 필드 존재 여부
        if (!isBlank(result.getVin())) score += 20;
        if (!isBlank(result.getVehicleNo())) score += 15;
        if (!isBlank(result.getFirstRegistratedAt())) score += 15;
        if (!isBlank(result.getManufactureYearMonth())) score += 20;

        // 채워진 전체 필드 수로 보정 (쓰레기 결과는 필드가 적음)
        int filledCount = countFilledFields(result);
        score += filledCount * 8;

        // 제원 필드 값 유효성 (쓰레기 값 감지)
        if (!isBlank(result.getOwnerName()) && result.getOwnerName().length() > 20) score -= 60;
        if (!isBlank(result.getAddress()) && result.getAddress().length() > 150) score -= 60;
        if (!isBlank(result.getEngineType()) && result.getEngineType().length() > 20) score -= 40;
        if (!isBlank(result.getModelCode()) && result.getModelCode().length() > 40) score -= 60;

        if (orientationSuspicious) score -= 40;
        if (rotation == 0) score += 5;
        return score;
    }

    private int countFilledFields(VehicleRegistration r) {
        int count = 0;
        if (!isBlank(r.getVin())) count++;
        if (!isBlank(r.getVehicleNo())) count++;
        if (!isBlank(r.getCarType())) count++;
        if (!isBlank(r.getVehicleUse())) count++;
        if (!isBlank(r.getModelName())) count++;
        if (!isBlank(r.getEngineType())) count++;
        if (!isBlank(r.getOwnerName())) count++;
        if (!isBlank(r.getOwnerId())) count++;
        if (r.getModelYear() != null) count++;
        if (!isBlank(r.getFuelType())) count++;
        if (!isBlank(r.getManufactureYearMonth())) count++;
        if (r.getDisplacement() != null) count++;
        if (!isBlank(r.getFirstRegistratedAt())) count++;
        if (!isBlank(r.getAddress())) count++;
        if (!isBlank(r.getModelCode())) count++;
        if (!isBlank(r.getLengthVal())) count++;
        if (!isBlank(r.getWidthVal())) count++;
        if (!isBlank(r.getHeightVal())) count++;
        if (!isBlank(r.getWeight())) count++;
        if (!isBlank(r.getSeating())) count++;
        if (!isBlank(r.getMaxLoad())) count++;
        if (!isBlank(r.getPower())) count++;
        return count;
    }

    private double fieldConfidence(VehicleRegistration result, String field) {
        Map<String, FieldEvidence> evidence = result.getEvidence();
        if (evidence == null) return 0.0;
        FieldEvidence ev = evidence.get(field);
        if (ev == null || ev.getConfidence() == null) return 0.0;
        return ev.getConfidence();
    }

    private boolean isOrientationSuspicious(List<OcrWord> words) {
        if (words == null || words.size() < 30) return false;

        int verticalish = 0;
        int horizontalish = 0;
        double avgAspect = 0.0;

        for (OcrWord w : words) {
            double width = Math.max(1.0, w.getWidth());
            double height = Math.max(1.0, w.getHeight());
            double aspect = height / width;
            avgAspect += aspect;
            if (aspect > 1.8) verticalish++;
            if (aspect < 0.8) horizontalish++;
        }
        avgAspect /= words.size();

        double verticalRatio = (double) verticalish / words.size();
        return verticalRatio > 0.48 && avgAspect > 1.35 && verticalish > horizontalish;
    }

    private String buildAttemptFileName(String fileName, int rotation) {
        if (rotation == 0) return fileName;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return fileName + "_r" + rotation + ".jpg";
        return fileName.substring(0, dot) + "_r" + rotation + fileName.substring(dot);
    }

    private void appendQualityReason(VehicleRegistration result, String note) {
        String cur = result.getQualityReason();
        if (cur == null || cur.isBlank()) {
            result.setQualityReason(note);
            return;
        }
        if (!cur.contains(note)) {
            result.setQualityReason(cur + " | " + note);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private VehicleRegistration parseWithPipeline(List<OcrWord> words) {
        NormalizedLayout layout = layoutNormalizer.normalize(words);
        DocumentTypeResult typeResult = documentTypeClassifier.classify(layout);

        Optional<DocumentParser> parserOpt = parsers.stream()
                .filter(p -> p.supports() == typeResult.getDocumentType())
                .findFirst();

        VehicleRegistration result;
        if (typeResult.getDocumentType() == DocumentType.UNKNOWN || parserOpt.isEmpty()) {
            result = new VehicleRegistration();
            result.setNeedsRetry(true);
            result.setQualityGatePassed(false);
            result.setQualityScore(0.0);
            if (typeResult.getDocumentType() == DocumentType.UNKNOWN) {
                result.setQualityReason("지원되지 않는 문서 타입 또는 분류 실패: " + typeResult.getReason());
            } else {
                result.setQualityReason("문서 파서 미등록: " + typeResult.getDocumentType());
            }
        } else {
            result = parserOpt.get().parse(layout);
        }

        result.setDocumentType(typeResult.getDocumentType().name());
        result.setDocumentTypeScore(typeResult.getScore());

        log.info("Parsed result: {}", result);
        return result;
    }

    private static class AttemptResult {
        private final int rotation;
        private final boolean preprocessedInput;
        private final List<OcrWord> words;
        private final VehicleRegistration result;
        private final boolean orientationSuspicious;
        private final double rank;

        private AttemptResult(
                int rotation,
                boolean preprocessedInput,
                List<OcrWord> words,
                VehicleRegistration result,
                boolean orientationSuspicious,
                double rank
        ) {
            this.rotation = rotation;
            this.preprocessedInput = preprocessedInput;
            this.words = words;
            this.result = result;
            this.orientationSuspicious = orientationSuspicious;
            this.rank = rank;
        }
    }
}
