package exps.cariv.domain.shipper.service;

import com.fasterxml.jackson.databind.JsonNode;
import exps.cariv.domain.clova.client.LlmFieldExtractor;
import exps.cariv.domain.shipper.dto.ParsedBizReg;
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
 * 사업자등록증 OCR 결과를 LLM으로 보정한다.
 * - 룰 기반 파서 결과를 우선 사용
 * - 누락/빈 필드만 LLM 결과로 채운다
 */
@Slf4j
@Component
public class BizRegLlmRefiner {

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

    private static final Pattern BIZ_NUMBER = Pattern.compile("(\\d{3})[-\\s]?(\\d{2})[-\\s]?(\\d{5})");

    /**
     * LLM으로 사업자등록증 OCR 결과를 보정한다.
     * 항상 실행하며, LLM 결과를 우선 사용한다 (파서 결과는 폴백).
     */
    public ParsedBizReg refineIfNeeded(ParsedBizReg base, UpstageResponse upstageRes) {
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
                    ? extractor.extractWithClaude(rawText, "biz_registration")
                    : extractor.extractWithGpt(rawText, "biz_registration");
            JsonNode json = extractor.parseResponse(llmRaw);
            if (json == null || !json.isObject()) return base;

            // LLM 결과 우선, 없으면 파서 결과 폴백
            String companyName = pickLlmFirst(rawField(json, "companyName"), base.companyName());
            String representativeName = pickLlmFirst(rawField(json, "representativeName"), base.representativeName());
            String bizNumber = pickLlmFirst(normalizeBizNumber(rawField(json, "bizNumber")), base.bizNumber());
            String bizType = pickLlmFirst(rawField(json, "bizType"), base.bizType());
            String bizCategory = pickLlmFirst(rawField(json, "bizCategory"), base.bizCategory());
            String bizAddress = pickLlmFirst(rawField(json, "bizAddress"), base.bizAddress());
            String openDate = pickLlmFirst(rawField(json, "openDate"), base.openDate());

            ParsedBizReg refined = new ParsedBizReg(
                    companyName, representativeName, bizNumber,
                    bizType, bizCategory, bizAddress, openDate
            );
            log.info("[BizReg LLM] Refined: {} -> {}", base, refined);
            return refined;
        } catch (Exception e) {
            log.warn("[BizReg LLM] 보정 실패, 원본 반환: {}", e.getMessage());
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

    private String normalizeBizNumber(String raw) {
        if (raw == null) return null;
        Matcher m = BIZ_NUMBER.matcher(raw.replaceAll("\\s+", ""));
        if (m.find()) {
            return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        }
        return null;
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
