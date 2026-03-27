package exps.cariv.domain.shipper.service;

import com.fasterxml.jackson.databind.JsonNode;
import exps.cariv.domain.clova.client.LlmFieldExtractor;
import exps.cariv.domain.shipper.dto.ParsedIdCard;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 신분증(주민등록증/운전면허증) OCR 결과를 LLM으로 보정한다.
 * - 룰 기반 파서 결과를 우선 사용
 * - 누락/빈 필드만 LLM 결과로 채운다
 */
@Slf4j
@Component
public class IdCardLlmRefiner {

    @Value("${app.ocr.llm.enabled:false}")
    private boolean enabled;

    @Value("${app.ocr.llm.provider:none}")
    private String configuredProvider;

    @Value("${app.ocr.llm.max-text-chars:12000}")
    private int maxTextChars;

    @Value("${app.ocr.llm.claude-api-key:}")
    private String claudeApiKey;

    @Value("${app.ocr.llm.openai-api-key:}")
    private String openaiApiKey;

    private static final Pattern KOR_ID = Pattern.compile("(\\d{6})\\s*[-]?\\s*(\\d{7}|\\d[*]{6})");

    /**
     * LLM으로 신분증 OCR 결과를 보정한다.
     * 항상 실행하며, LLM 결과를 우선 사용한다 (파서 결과는 폴백).
     */
    public ParsedIdCard refineIfNeeded(ParsedIdCard base, UpstageResponse upstageRes) {
        if (base == null) return null;
        if (!enabled) return base;

        String provider = resolveProvider();
        if (provider == null) return base;

        String rawText = collectRawText(upstageRes);
        if (rawText == null || rawText.isBlank()) return base;
        if (maxTextChars > 0 && rawText.length() > maxTextChars) {
            rawText = rawText.substring(0, maxTextChars);
        }

        try {
            LlmFieldExtractor extractor = new LlmFieldExtractor(claudeApiKey, openaiApiKey);
            String llmRaw = "haiku".equals(provider)
                    ? extractor.extractWithClaude(rawText, "id_card")
                    : extractor.extractWithGpt(rawText, "id_card");
            JsonNode json = extractor.parseResponse(llmRaw);
            if (json == null || !json.isObject()) return base;

            // LLM 결과 우선, 없으면 파서 결과 폴백
            String holderName = pickLlmFirst(rawField(json, "holderName"), base.holderName());
            String idNumber = pickLlmFirst(normalizeIdNumber(rawField(json, "idNumber")), base.idNumber());
            String idAddress = pickLlmFirst(rawField(json, "idAddress"), base.idAddress());
            String issueDate = pickLlmFirst(rawField(json, "issueDate"), base.issueDate());

            ParsedIdCard refined = new ParsedIdCard(holderName, idNumber, idAddress, issueDate);
            log.info("[IdCard LLM] Refined: {} -> {}", base, refined);
            return refined;
        } catch (Exception e) {
            log.warn("[IdCard LLM] 보정 실패, 원본 반환: {}", e.getMessage());
            return base;
        }
    }

    private String resolveProvider() {
        if (configuredProvider == null) return null;
        String p = configuredProvider.trim().toLowerCase(Locale.ROOT);
        if (("haiku".equals(p) || "claude".equals(p)) && !isBlank(claudeApiKey)) return "haiku";
        if (("gpt".equals(p) || "openai".equals(p)) && !isBlank(openaiApiKey)) return "gpt";
        return null;
    }

    private String collectRawText(UpstageResponse res) {
        if (res == null || res.elements() == null) return null;
        return res.elements().stream()
                .map(UpstageElement::content)
                .filter(Objects::nonNull)
                .map(c -> {
                    if (c.text() != null && !c.text().isBlank()) return c.text();
                    if (c.markdown() != null && !c.markdown().isBlank()) return c.markdown();
                    return "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /** LLM이 예시값을 그대로 반환하는 경우 차단 */
    private static final Set<String> FAKE_ID_NUMBERS = Set.of(
            "900101-1234567", "000000-0000000", "123456-1234567",
            "900101-2234567", "800101-1234567", "990101-1234567"
    );

    private String normalizeIdNumber(String raw) {
        if (raw == null) return null;
        String compact = raw.replaceAll("\\s+", "");
        Matcher m = KOR_ID.matcher(compact);
        if (m.find()) {
            String front = m.group(1);
            String back = m.group(2);
            String result = front + "-" + back;
            // LLM이 만들어낸 가짜 번호 차단
            if (FAKE_ID_NUMBERS.contains(result)) {
                log.warn("[IdCard LLM] 가짜 주민번호 감지, null 처리: {}", result);
                return null;
            }
            return result;
        }
        return compact.isBlank() ? null : compact;
    }

    /** LLM 값 우선, 없으면 파서 값 폴백 */
    private String pickLlmFirst(String llmValue, String parserValue) {
        if (!isBlank(llmValue)) return llmValue;
        return parserValue;
    }

    private String rawField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String text = v.asText().trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
