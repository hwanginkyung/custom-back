package exps.cariv.domain.clova.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LLM(Claude Haiku / GPT-4o-mini) 을 이용한 필드 추출기
 * OCR raw 텍스트를 LLM에 보내서 구조화된 JSON을 받아옴
 */
public class LlmFieldExtractor {

    private final ObjectMapper om = new ObjectMapper();

    private final String claudeApiKey;
    private final String openaiApiKey;

    /** 429 재시도 설정 */
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;   // 1초
    private static final long MAX_DELAY_MS = 10000;    // 10초

    /** 최소 요청 간격 (Tier 1: 50 RPM → ~1.2초 간격) */
    private static final long MIN_REQUEST_INTERVAL_MS = 1300;
    private static final Object CLAUDE_LOCK = new Object();
    private static final Object GPT_LOCK = new Object();
    private static volatile long lastClaudeRequestTime = 0;
    private static volatile long lastGptRequestTime = 0;

    public LlmFieldExtractor(String claudeApiKey, String openaiApiKey) {
        this.claudeApiKey = claudeApiKey;
        this.openaiApiKey = openaiApiKey;
    }

    // ── System Prompt ──
    private static final String SYSTEM_PROMPT = """
            당신은 한국 자동차 서류 OCR 텍스트에서 정보를 추출하는 전문가입니다.

            규칙:
            1. OCR 텍스트에 있는 값을 그대로 추출하세요. 임의로 변환하거나 추측하지 마세요.
            2. 주민등록번호/생년월일은 OCR 원문 그대로 유지 (예: "651011-*******", "110111-2177388")
            3. 소유자명에 "(주)", "(유)" 등 법인 표기가 있으면 그대로 포함 (예: "현대글로비스(주)")
            4. "(상품용)", "*상품용+" 같은 문구는 소유자명에서 제거
            5. 발행기관(issuer)은 공백 없이 붙여쓰기 (예: "인천광역시계양구청장")
            6. 숫자 필드(seizureCount, mortgageCount)는 문자열로 반환 (예: "0", "2")
            7. 날짜는 YYYY-MM-DD 형식
            8. 차량번호는 공백 없이 (예: "68마3644", "01도4054")
            9. 확인할 수 없는 필드는 null
            10. 반드시 순수 JSON만 반환. 마크다운 코드블록(```)이나 설명 텍스트 없이.
            """;

    // ── 말소사실증명서 프롬프트 ──
    private static final String DEREGISTRATION_PROMPT = """
            아래는 자동차말소등록사실증명서를 OCR로 읽은 텍스트입니다.
            다음 필드를 추출하여 JSON으로 반환해주세요.

            필드:
            - vehicleNo: 자동차등록번호 (예: "68마3644")
            - carType: 차종 (승용/승합/화물/특수)
            - mileage: 주행거리 (숫자 문자열, km 단위)
            - modelName: 차명 (예: "쏘나타", "K7")
            - vin: 차대번호 (17자리 영문+숫자)
            - engineType: 원동기형식 (예: "G4FC", "D4CB", "SR18")
            - modelYear: 모델연도 (숫자, 예: 2015)
            - vehicleUse: 용도 (자가용/사업용)
            - specManagementNo: 제원관리번호 (예: "A01-1-00043-0002-1208")
            - firstRegistratedAt: 최초등록일 (YYYY-MM-DD)
            - ownerName: 소유자 성명/명칭 - "(주)" 등 법인표기 포함, "(상품용)" 제거
            - ownerId: 생년월일 또는 법인등록번호 - OCR 원문 그대로 (예: "110111-2177388", "651011-*******")
            - deregistrationDate: 말소등록일 (YYYY-MM-DD)
            - deregistrationReason: 말소등록구분 (예: "수출예정(수출말소)", "해체말소")
            - certificateUse: 증명서 용도 (예: "증명용")
            - seizureCount: 압류 건수 (문자열, 예: "0")
            - mortgageCount: 저당권 건수 (문자열, 예: "0")
            - businessUsePeriod: 사업용 사용기간 (예: "2020-01-01 ~ 2023-05-15", 없으면 null)
            - issueDate: 발행 연월일 (YYYY-MM-DD)
            - issuer: 발행 기관 - 공백 없이 (예: "인천광역시계양구청장")

            OCR 텍스트:
            """;

    // ── 자동차등록증 프롬프트 ──
    private static final String REGISTRATION_PROMPT = """
            아래는 자동차등록증을 OCR로 읽은 텍스트입니다.
            다음 필드를 추출하여 JSON으로 반환해주세요.

            필드:
            - vin: 차대번호 (17자리 영문+숫자)
            - vehicleNo: 자동차등록번호 (예: "12가3456")
            - carType: 차종 - "대형승용", "소형승용", "중형승합" 등 OCR 원문 그대로
            - vehicleUse: 용도 (자가용/사업용)
            - modelName: 차명 (예: "쏘나타", "K7", "아반떼") - OCR 원문 그대로
            - engineType: 원동기형식 (예: "G4FC", "D4CB")
            - ownerName: 소유자 성명/명칭 - "(주)" 등 법인표기 포함, "(상품용)" 제거
            - ownerId: 주민등록번호 또는 법인등록번호 - OCR 원문 그대로 (마스킹 포함, 예: "651011-*******")
            - modelYear: 모델연도 (숫자, 예: 2015)
            - fuelType: 연료 (휘발유/경유/LPG/전기/하이브리드 등)
            - manufactureYearMonth: 제작연월 (YYYY-MM 형식, 예: "2015-03")
            - displacement: 배기량 (숫자만, cc 단위, 예: 1999)
            - firstRegistratedAt: 최초등록일 (YYYY-MM-DD)
            - address: 사용본거지/주소 - OCR 원문 그대로
            - modelCode: 형식코드 (예: "JA55T26")
            - lengthVal: 길이 (숫자만, mm 단위)
            - widthVal: 너비 (숫자만, mm 단위)
            - heightVal: 높이 (숫자만, mm 단위)
            - weight: 총중량 (숫자만, kg 단위)
            - seating: 승차정원 (숫자만, 예: "5")
            - maxLoad: 최대적재량 (숫자만, kg 단위, 없으면 null)
            - power: 정격출력 (예: "110/6000kw", OCR 원문 그대로)

            OCR 텍스트:
            """;

    /**
     * Claude Haiku로 필드 추출 (429 rate limit 자동 재시도)
     */
    public String extractWithClaude(String ocrText, String docType) throws IOException {
        String userPrompt = getPrompt(docType) + ocrText;

        String requestBody = om.writeValueAsString(Map.of(
                "model", "claude-3-haiku-20240307",
                "max_tokens", 1500,
                "system", SYSTEM_PROMPT,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        ));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            throttle(CLAUDE_LOCK, "claude");

            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", claudeApiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body;
            try (InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (code == 429 || code == 529) {
                if (attempt < MAX_RETRIES) {
                    long delay = retryDelay(attempt, conn);
                    System.err.printf("[Claude] %d응답, %dms 후 재시도 (%d/%d)%n", code, delay, attempt + 1, MAX_RETRIES);
                    sleep(delay);
                    continue;
                }
                throw new IOException("Claude API rate limit: " + MAX_RETRIES + "회 재시도 실패 " + code + ": " + body);
            }

            if (code != 200) {
                throw new IOException("Claude API error " + code + ": " + body);
            }

            // 응답에서 content[0].text 추출
            JsonNode root = om.readTree(body);
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                return content.get(0).get("text").asText();
            }
            return body;
        }
        throw new IOException("Claude API: 최대 재시도 초과");
    }

    /**
     * GPT-4o-mini로 필드 추출 (429 rate limit 자동 재시도)
     */
    public String extractWithGpt(String ocrText, String docType) throws IOException {
        String userPrompt = getPrompt(docType) + ocrText;

        String requestBody = om.writeValueAsString(Map.of(
                "model", "gpt-4o-mini",
                "max_tokens", 1500,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        ));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            throttle(GPT_LOCK, "gpt");

            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openai.com/v1/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + openaiApiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body;
            try (InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (code == 429) {
                if (attempt < MAX_RETRIES) {
                    long delay = retryDelay(attempt, conn);
                    System.err.printf("[GPT] 429응답, %dms 후 재시도 (%d/%d)%n", delay, attempt + 1, MAX_RETRIES);
                    sleep(delay);
                    continue;
                }
                throw new IOException("OpenAI API rate limit: " + MAX_RETRIES + "회 재시도 실패: " + body);
            }

            if (code != 200) {
                throw new IOException("OpenAI API error " + code + ": " + body);
            }

            // 응답에서 choices[0].message.content 추출
            JsonNode root = om.readTree(body);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).get("message").get("content").asText();
            }
            return body;
        }
        throw new IOException("OpenAI API: 최대 재시도 초과");
    }

    // ── Rate limit 유틸 ──

    /**
     * 최소 요청 간격을 보장하는 스로틀링
     */
    private void throttle(Object lock, String provider) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long lastTime = "claude".equals(provider) ? lastClaudeRequestTime : lastGptRequestTime;
            long elapsed = now - lastTime;
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
            }
            if ("claude".equals(provider)) {
                lastClaudeRequestTime = System.currentTimeMillis();
            } else {
                lastGptRequestTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * Exponential backoff + jitter 로 재시도 대기시간 계산.
     * Retry-After 헤더가 있으면 우선 사용.
     */
    private long retryDelay(int attempt, HttpURLConnection conn) {
        // Retry-After 헤더 확인
        String retryAfter = conn.getHeaderField("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0 && seconds < 120) {
                    return seconds * 1000 + ThreadLocalRandom.current().nextLong(200);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // exponential backoff: 1s, 2s, 4s + jitter
        long delay = BASE_DELAY_MS * (1L << attempt);
        delay = Math.min(delay, MAX_DELAY_MS);
        delay += ThreadLocalRandom.current().nextLong(delay / 4 + 1);
        return delay;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String getPrompt(String docType) {
        return "deregistration".equals(docType) ? DEREGISTRATION_PROMPT : REGISTRATION_PROMPT;
    }

    /**
     * LLM 응답 JSON 문자열을 파싱하여 JsonNode로 변환
     * ```json ... ``` 마크다운 블록 처리
     */
    public JsonNode parseResponse(String llmResponse) {
        if (llmResponse == null) return null;
        String cleaned = llmResponse.trim();
        // ```json ... ``` 블록 제거
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastBacktick = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        try {
            return om.readTree(cleaned);
        } catch (Exception e) {
            System.err.println("JSON parse error: " + e.getMessage());
            System.err.println("Raw: " + llmResponse);
            return null;
        }
    }
}
