package exps.cariv.domain.contract.service;

import com.fasterxml.jackson.databind.JsonNode;
import exps.cariv.domain.clova.client.LlmFieldExtractor;
import exps.cariv.domain.contract.dto.ContractParsed;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 매매계약서 OCR 결과를 LLM으로 보정한다.
 * LLM 결과를 우선 사용하고, 파서 결과는 폴백.
 */
@Slf4j
@Component
public class ContractLlmRefiner {

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

    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    public ContractParsed refineIfNeeded(ContractParsed base, UpstageResponse upstageRes) {
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
                    ? extractor.extractWithClaude(rawText, "contract")
                    : extractor.extractWithGpt(rawText, "contract");
            JsonNode json = extractor.parseResponse(llmRaw);
            if (json == null || !json.isObject()) return base;

            String registrationNo = pickLlmFirst(rawField(json, "registrationNo"), base.registrationNo());
            String vehicleType = pickLlmFirst(rawField(json, "vehicleType"), base.vehicleType());
            String model = pickLlmFirst(rawField(json, "model"), base.model());
            String chassisNo = pickLlmFirst(normalizeVin(rawField(json, "chassisNo")), base.chassisNo());

            ContractParsed refined = new ContractParsed(registrationNo, vehicleType, model, chassisNo);
            log.info("[Contract LLM] Refined: {} -> {}", base, refined);
            return refined;
        } catch (Exception e) {
            log.warn("[Contract LLM] 보정 실패, 원본 반환: {}", e.getMessage());
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

    private String normalizeVin(String raw) {
        if (raw == null) return null;
        String upper = raw.replaceAll("\\s+", "").toUpperCase();
        Matcher m = VIN_PATTERN.matcher(upper);
        if (m.find()) return m.group();
        return upper.isEmpty() ? null : upper;
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
