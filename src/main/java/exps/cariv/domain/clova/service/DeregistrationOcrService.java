package exps.cariv.domain.clova.service;

import exps.cariv.domain.clova.client.OcrClient;
import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.TextLine;
import exps.cariv.domain.clova.dto.VehicleDeregistration;
import exps.cariv.domain.clova.parser.DeregistrationBboxParser;
import exps.cariv.domain.clova.parser.LineGrouper;
import exps.cariv.domain.clova.preprocess.DocumentImagePreprocessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 말소사실증명서 OCR 처리 서비스.
 * 파이프라인: 전처리 → OCR → 회전보정 → 규칙파서 → LLM 보정
 */
@Slf4j
@Service("clovaDeregistrationOcrService")
@RequiredArgsConstructor
public class DeregistrationOcrService {

    private final OcrClient ocrClient;
    private final DocumentImagePreprocessor imagePreprocessor;

    @Autowired(required = false)
    private DeregistrationLlmRefiner deregistrationLlmRefiner;

    @Value("${app.ocr.parser.line-y-threshold-ratio:0.5}")
    private double lineYThresholdRatio;

    /**
     * 이미지 파일 경로로 말소사실증명서 OCR 처리
     */
    public VehicleDeregistration processFile(Path imagePath) throws IOException {
        log.info("Processing deregistration certificate: {}", imagePath);
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String fileName = imagePath.getFileName() != null ? imagePath.getFileName().toString() : "image.jpg";
        return processBytes(imageBytes, fileName);
    }

    /**
     * 업로드된 이미지 바이트로 말소사실증명서 OCR 처리
     */
    public VehicleDeregistration processBytes(byte[] imageBytes, String fileName) throws IOException {
        log.info("Processing deregistration certificate from upload: {}", fileName);

        // 원본 이미지로 OCR (전처리 제거 — CLOVA는 원본 컬러 이미지에서 더 정확)
        List<AttemptResult> attempts = new ArrayList<>();
        AttemptResult base = runAttempt(imageBytes, fileName, 0);
        attempts.add(base);

        if (shouldTryRotation(base)) {
            attempts.add(runAttempt(imagePreprocessor.rotate(imageBytes, 90), fileName, 90));
            attempts.add(runAttempt(imagePreprocessor.rotate(imageBytes, 270), fileName, 270));
        }

        AttemptResult best = pickBest(attempts);

        // 마지막까지 실패면 180도까지 추가 확인
        if (Boolean.TRUE.equals(best.result.getNeedsRetry())) {
            attempts.add(runAttempt(imagePreprocessor.rotate(imageBytes, 180), fileName, 180));
            best = pickBest(attempts);
        }

        VehicleDeregistration finalResult = best.result;

        if (best.rotation != 0) {
            appendQualityReason(finalResult, "회전보정 적용(" + best.rotation + "도)");
        }

        // LLM 보정
        if (deregistrationLlmRefiner != null) {
            finalResult = deregistrationLlmRefiner.refineIfNeeded(
                    finalResult,
                    best.words,
                    ocrClient.provider(),
                    fileName
            );
        }

        log.info(
                "Deregistration OCR provider={} attempts={} selectedRotation={} selectedScore={} needsRetry={}",
                ocrClient.provider(),
                attempts.size(),
                best.rotation,
                round4(best.rank),
                finalResult.getNeedsRetry()
        );
        return finalResult;
    }

    private AttemptResult runAttempt(byte[] bytes, String fileName, int rotation) throws IOException {
        String attemptName = buildAttemptFileName(fileName, rotation);
        List<OcrWord> words = ocrClient.recognize(bytes, attemptName);
        log.info("OCR returned {} words (rotation={}°)", words.size(), rotation);

        List<TextLine> lines = LineGrouper.group(words, lineYThresholdRatio);
        DeregistrationBboxParser parser = new DeregistrationBboxParser(words, lines);
        VehicleDeregistration result = parser.parse();

        double rank = scoreAttempt(result, rotation);
        return new AttemptResult(rotation, words, result, rank);
    }

    private boolean shouldTryRotation(AttemptResult base) {
        if (Boolean.TRUE.equals(base.result.getNeedsRetry())) return true;

        // 핵심 필드 비어 있으면 회전 재시도
        return isBlank(base.result.getVin())
                || isBlank(base.result.getVehicleNo())
                || isBlank(base.result.getDeregistrationDate());
    }

    private AttemptResult pickBest(List<AttemptResult> attempts) {
        return attempts.stream()
                .max((a, b) -> Double.compare(a.rank, b.rank))
                .orElseThrow();
    }

    private double scoreAttempt(VehicleDeregistration result, int rotation) {
        double score = 0.0;

        if (!Boolean.TRUE.equals(result.getNeedsRetry())) score += 200;

        Double qs = result.getQualityScore();
        score += (qs != null ? qs : 0.0) * 120;

        Double dts = result.getDocumentTypeScore();
        score += (dts != null ? dts : 0.0) * 20;

        // 핵심 필드 존재 여부
        if (!isBlank(result.getVin())) score += 30;
        if (!isBlank(result.getVehicleNo())) score += 25;
        if (!isBlank(result.getDeregistrationDate())) score += 25;
        if (!isBlank(result.getFirstRegistratedAt())) score += 15;
        if (!isBlank(result.getOwnerName())) score += 10;
        if (!isBlank(result.getOwnerId())) score += 10;
        if (!isBlank(result.getSeizureCount())) score += 10;
        if (!isBlank(result.getDeregistrationReason())) score += 10;

        // 채워진 전체 필드 수
        int filled = countFilled(result);
        score += filled * 8;

        if (rotation == 0) score += 5;
        return score;
    }

    private int countFilled(VehicleDeregistration r) {
        int c = 0;
        if (!isBlank(r.getVehicleNo())) c++;
        if (!isBlank(r.getCarType())) c++;
        if (!isBlank(r.getMileage())) c++;
        if (!isBlank(r.getModelName())) c++;
        if (!isBlank(r.getVin())) c++;
        if (!isBlank(r.getEngineType())) c++;
        if (r.getModelYear() != null) c++;
        if (!isBlank(r.getVehicleUse())) c++;
        if (!isBlank(r.getSpecManagementNo())) c++;
        if (!isBlank(r.getFirstRegistratedAt())) c++;
        if (!isBlank(r.getOwnerName())) c++;
        if (!isBlank(r.getOwnerId())) c++;
        if (!isBlank(r.getDeregistrationDate())) c++;
        if (!isBlank(r.getDeregistrationReason())) c++;
        if (!isBlank(r.getCertificateUse())) c++;
        if (!isBlank(r.getSeizureCount())) c++;
        if (!isBlank(r.getMortgageCount())) c++;
        if (!isBlank(r.getBusinessUsePeriod())) c++;
        if (!isBlank(r.getIssueDate())) c++;
        if (!isBlank(r.getIssuer())) c++;
        return c;
    }

    private String buildAttemptFileName(String fileName, int rotation) {
        if (rotation == 0) return fileName;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return fileName + "_r" + rotation + ".jpg";
        return fileName.substring(0, dot) + "_r" + rotation + fileName.substring(dot);
    }

    private void appendQualityReason(VehicleDeregistration result, String note) {
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

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static class AttemptResult {
        private final int rotation;
        private final List<OcrWord> words;
        private final VehicleDeregistration result;
        private final double rank;

        private AttemptResult(int rotation, List<OcrWord> words, VehicleDeregistration result, double rank) {
            this.rotation = rotation;
            this.words = words;
            this.result = result;
            this.rank = rank;
        }
    }
}
