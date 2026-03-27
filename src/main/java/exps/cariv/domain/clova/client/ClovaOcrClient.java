package exps.cariv.domain.clova.client;

import exps.cariv.domain.clova.dto.OcrWord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Naver CLOVA OCR General API 클라이언트
 */
@Slf4j
@Component
public class ClovaOcrClient implements OcrClient {

    @Value("${app.ocr.clova.secret-key}")
    private String secretKey;

    @Value("${app.ocr.clova.api-url}")
    private String invokeUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Object RATE_LIMIT_LOCK = new Object();
    private static long lastRequestEpochMs = 0L;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long MIN_REQUEST_INTERVAL_MS = 350L;
    private static final long RETRY_BASE_BACKOFF_MS = 800L;
    private static final long RETRY_MAX_BACKOFF_MS = 4000L;

    /**
     * 이미지 파일을 OCR 처리하여 OcrWord 리스트 반환
     */
    public List<OcrWord> recognize(Path imagePath) throws IOException {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String fileName = imagePath.getFileName().toString();
        String format = getFormat(fileName);

        return callApiWithRetry(imageBytes, format, fileName);
    }

    /**
     * 바이트 배열 이미지를 OCR 처리
     */
    public List<OcrWord> recognize(byte[] imageBytes, String fileName) throws IOException {
        String format = getFormat(fileName);
        return callApiWithRetry(imageBytes, format, fileName);
    }

    @Override
    public String provider() {
        return "clova";
    }

    private List<OcrWord> callApiWithRetry(byte[] imageBytes, String format, String fileName) throws IOException {
        long backoffMs = RETRY_BASE_BACKOFF_MS;
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                waitForRateLimitSlot();
                return callApiOnce(imageBytes, format, fileName);
            } catch (RetryableClovaException e) {
                lastError = e;
                if (attempt == MAX_RETRY_ATTEMPTS) break;

                long sleepMs = addJitter(backoffMs);
                log.warn(
                        "Clova OCR retryable error status={} attempt={}/{} backoffMs={}",
                        e.getStatusCode(),
                        attempt,
                        MAX_RETRY_ATTEMPTS,
                        sleepMs
                );
                sleepQuietly(sleepMs);
                backoffMs = Math.min(backoffMs * 2, RETRY_MAX_BACKOFF_MS);
            }
        }

        if (lastError != null) throw lastError;
        throw new IOException("Clova OCR failed: unknown error");
    }

    private List<OcrWord> callApiOnce(byte[] imageBytes, String format, String fileName) throws IOException {
        String boundary = "----" + UUID.randomUUID().toString().replace("-", "");

        // invoke URL 끝에 /general 붙이기 (이미 붙어있으면 skip)
        String apiUrl = invokeUrl.endsWith("/general")
                ? invokeUrl : invokeUrl + "/general";
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("X-OCR-SECRET", secretKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        // JSON message part
        String messageJson = objectMapper.writeValueAsString(Map.of(
                "version", "V2",
                "requestId", UUID.randomUUID().toString(),
                "timestamp", System.currentTimeMillis(),
                "images", List.of(Map.of(
                        "format", format,
                        "name", fileName
                ))
        ));

        try (OutputStream os = conn.getOutputStream()) {
            // message part
            writeMultipartField(os, boundary, "message", messageJson);
            // file part
            writeMultipartFile(os, boundary, "file", fileName, imageBytes, "application/octet-stream");
            // end boundary
            os.write(("--" + boundary + "--\r\n").getBytes());
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        String responseBody;

        try (InputStream is = (responseCode >= 200 && responseCode < 300)
                ? conn.getInputStream() : conn.getErrorStream()) {
            responseBody = new String(is.readAllBytes());
        }

        if (responseCode != 200) {
            log.error("OCR API error: {} - {}", responseCode, responseBody);
            if (isRetryable(responseCode, responseBody)) {
                throw new RetryableClovaException(responseCode, "OCR API returned " + responseCode + ": " + responseBody);
            }
            throw new IOException("OCR API returned " + responseCode + ": " + responseBody);
        }

        log.debug("OCR raw response: {}", responseBody);
        return parseResponse(responseBody);
    }

    private boolean isRetryable(int statusCode, String responseBody) {
        if (statusCode == 429 || (statusCode >= 500 && statusCode <= 599)) return true;
        return responseBody != null && responseBody.contains("\"code\":\"0025\"");
    }

    private void waitForRateLimitSlot() {
        long sleepMs = 0L;
        synchronized (RATE_LIMIT_LOCK) {
            long now = System.currentTimeMillis();
            long earliest = lastRequestEpochMs + MIN_REQUEST_INTERVAL_MS;
            if (now < earliest) {
                sleepMs = earliest - now;
            }
            lastRequestEpochMs = now + sleepMs;
        }
        sleepQuietly(sleepMs);
    }

    private long addJitter(long backoffMs) {
        long jitter = ThreadLocalRandom.current().nextLong(0, 301);
        return backoffMs + jitter;
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Clova retry", ie);
        }
    }

    private List<OcrWord> parseResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        List<OcrWord> words = new ArrayList<>();

        JsonNode images = root.get("images");
        if (images == null || !images.isArray() || images.isEmpty()) {
            log.warn("No images in OCR response");
            return words;
        }

        JsonNode firstImage = images.get(0);
        String inferResult = firstImage.has("inferResult") ? firstImage.get("inferResult").asText() : "";
        if (!"SUCCESS".equals(inferResult)) {
            log.warn("OCR inferResult: {}", inferResult);
            return words;
        }

        JsonNode fields = firstImage.get("fields");
        if (fields == null || !fields.isArray()) {
            return words;
        }

        for (JsonNode field : fields) {
            String text = field.get("inferText").asText();
            double confidence = field.has("inferConfidence")
                    ? field.get("inferConfidence").asDouble() : 0;
            boolean lineBreak = field.has("lineBreak")
                    && field.get("lineBreak").asBoolean();

            JsonNode vertices = field.get("boundingPoly").get("vertices");
            if (vertices == null || !vertices.isArray() || vertices.isEmpty()) {
                continue;
            }

            // polygon vertex 순서가 이미지마다 달라질 수 있어 min/max 기반으로 축정렬 bbox 생성
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;

            for (JsonNode v : vertices) {
                double vx = v.has("x") ? v.get("x").asDouble() : 0;
                double vy = v.has("y") ? v.get("y").asDouble() : 0;
                minX = Math.min(minX, vx);
                minY = Math.min(minY, vy);
                maxX = Math.max(maxX, vx);
                maxY = Math.max(maxY, vy);
            }

            if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE) {
                continue;
            }

            double w = Math.max(0, maxX - minX);
            double h = Math.max(0, maxY - minY);
            words.add(new OcrWord(text, minX, minY, w, h, confidence, lineBreak));
        }

        log.info("OCR recognized {} words", words.size());
        return words;
    }

    private void writeMultipartField(OutputStream os, String boundary,
                                      String fieldName, String value) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n"
                + "Content-Type: application/json\r\n\r\n";
        os.write(header.getBytes());
        os.write(value.getBytes());
        os.write("\r\n".getBytes());
    }

    private void writeMultipartFile(OutputStream os, String boundary,
                                     String fieldName, String fileName,
                                     byte[] data, String contentType) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        os.write(header.getBytes());
        os.write(data);
        os.write("\r\n".getBytes());
    }

    private String getFormat(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) return "jpg";
        if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return "tiff";
        if (lower.endsWith(".bmp")) return "bmp";
        if (lower.endsWith(".pdf")) return "pdf";
        return "jpg"; // default
    }

    private static class RetryableClovaException extends IOException {
        private final int statusCode;

        private RetryableClovaException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int getStatusCode() {
            return statusCode;
        }
    }
}
