package exps.cariv.domain.clova.service;

import exps.cariv.domain.clova.client.LlmFieldExtractor;
import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.VehicleDeregistration;
import exps.cariv.domain.clova.parser.LineGrouper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 말소사실증명서 룰 파서 결과를 LLM으로 선택 보완한다.
 * - 룰 기반 파서를 항상 우선 사용
 * - 낮은 confidence / 검증 실패 필드만 LLM 결과로 교체
 *
 * 약한 필드 (테스트 기준):
 *   ownerId (16/33), seizureCount (23/33), ownerName (24/33)
 */
@Slf4j
@Component
public class DeregistrationLlmRefiner {

    private enum Provider {
        NONE, HAIKU, GPT;

        static Provider from(String raw) {
            if (raw == null) return NONE;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "haiku", "claude" -> HAIKU;
                case "gpt", "openai" -> GPT;
                default -> NONE;
            };
        }
    }

    private static final Pattern PLATE = Pattern.compile("\\d{2,3}[가-힣]\\d{4}");
    private static final Pattern VIN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern DATE_YMD = Pattern.compile("(?<!\\d)(\\d{4})\\D*(\\d{1,2})\\D*(\\d{1,2})(?!\\d)");
    private static final Pattern OWNER_ID = Pattern.compile("(\\d{6})\\s*[-:]?\\s*([\\d*Xx]{7})");
    private static final Pattern OWNER_ID_CANONICAL = Pattern.compile("^\\d{6}-[\\d*]{7}$");
    private static final Pattern COUNT_PATTERN = Pattern.compile("^\\d{1,3}$");

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

    @Value("${app.ocr.parser.line-y-threshold-ratio:0.5}")
    private double lineYThresholdRatio = 0.5;

    public VehicleDeregistration refineIfNeeded(
            VehicleDeregistration base,
            List<OcrWord> words,
            String ocrProvider,
            String fileName
    ) {
        if (base == null) return null;
        if (!enabled) return base;
        if (Boolean.FALSE.equals(base.getQualityGatePassed())) return base;

        Provider provider = resolveProvider();
        if (provider == Provider.NONE) return base;
        if (!shouldRun(base)) return base;

        String pageText = buildPageText(words);
        if (pageText == null || pageText.isBlank()) return base;
        if (maxTextChars > 0 && pageText.length() > maxTextChars) {
            pageText = pageText.substring(0, maxTextChars);
        }

        try {
            LlmFieldExtractor extractor = new LlmFieldExtractor(claudeApiKey, openaiApiKey);
            String raw = provider == Provider.HAIKU
                    ? extractor.extractWithClaude(pageText, "deregistration")
                    : extractor.extractWithGpt(pageText, "deregistration");
            JsonNode json = extractor.parseResponse(raw);
            if (json == null || !json.isObject()) return base;

            List<String> patchedFields = patchFields(base, json);
            if (!patchedFields.isEmpty()) {
                appendReason(base, String.format(
                        Locale.ROOT,
                        "LLM(%s) 보완 적용 fields=%s ocr=%s file=%s",
                        provider.name().toLowerCase(Locale.ROOT),
                        String.join(",", patchedFields),
                        ocrProvider,
                        fileName
                ));
                // 핵심 필드 충족 시 needsRetry 해제
                if (isCoreValid(base)) {
                    base.setNeedsRetry(false);
                }
            }
        } catch (Exception e) {
            log.warn("DeregistrationLlmRefiner skipped due to error: {}", e.getMessage());
        }
        return base;
    }

    // ═══ Provider 확인 ═══

    private Provider resolveProvider() {
        Provider provider = Provider.from(configuredProvider);
        if (provider == Provider.HAIKU && (claudeApiKey == null || claudeApiKey.isBlank())) {
            log.warn("ocr.llm.provider=haiku but claude api key is empty");
            return Provider.NONE;
        }
        if (provider == Provider.GPT && (openaiApiKey == null || openaiApiKey.isBlank())) {
            log.warn("ocr.llm.provider=gpt but openai api key is empty");
            return Provider.NONE;
        }
        return provider;
    }

    // ═══ LLM 호출 필요 여부 판단 ═══

    private boolean shouldRun(VehicleDeregistration reg) {
        if (Boolean.TRUE.equals(reg.getNeedsRetry())) return true;

        // 약한 필드: ownerId, seizureCount, mortgageCount, ownerName
        // 핵심 필드: vin, vehicleNo, firstRegistratedAt, deregistrationDate
        return isBlankOrInvalid(reg.getVin(), this::isValidVin)
                || isBlankOrInvalid(reg.getVehicleNo(), this::isValidPlate)
                || isBlankOrInvalid(reg.getFirstRegistratedAt(), this::isValidDate)
                || isBlankOrInvalid(reg.getDeregistrationDate(), this::isValidDate)
                || isBlankOrInvalid(reg.getOwnerId(), this::isValidOwnerId)
                || isBlankOrInvalid(reg.getSeizureCount(), this::isValidCount)
                || isBlankOrInvalid(reg.getMortgageCount(), this::isValidCount)
                || isBlank(reg.getOwnerName())
                || isBlankOrInvalid(reg.getIssueDate(), this::isValidDate)
                || isBlank(reg.getIssuer())
                || isBlankOrInvalid(reg.getEngineType(), this::isValidEngineType);
    }

    private boolean isBlankOrInvalid(String value, java.util.function.Predicate<String> validator) {
        if (value == null || value.isBlank()) return true;
        return !validator.test(value);
    }

    // ═══ 필드 패칭 ═══

    private List<String> patchFields(VehicleDeregistration reg, JsonNode json) {
        List<String> patched = new ArrayList<>();

        // VIN
        if (isBlankOrInvalid(reg.getVin(), this::isValidVin)) {
            String value = normalizeVin(rawField(json, "vin"));
            if (value != null && isValidVin(value)) {
                reg.setVin(value);
                patched.add("vin");
            }
        }

        // 차량번호
        if (isBlankOrInvalid(reg.getVehicleNo(), this::isValidPlate)) {
            String value = normalizePlate(rawField(json, "vehicleNo"));
            if (value != null && isValidPlate(value)) {
                reg.setVehicleNo(value);
                patched.add("vehicleNo");
            }
        }

        // 최초등록일
        if (isBlankOrInvalid(reg.getFirstRegistratedAt(), this::isValidDate)) {
            String value = normalizeDate(rawField(json, "firstRegistratedAt"));
            if (value != null && isValidDate(value)) {
                reg.setFirstRegistratedAt(value);
                patched.add("firstRegistratedAt");
            }
        }

        // 말소등록일
        if (isBlankOrInvalid(reg.getDeregistrationDate(), this::isValidDate)) {
            String value = normalizeDate(rawField(json, "deregistrationDate"));
            if (value != null && isValidDate(value)) {
                reg.setDeregistrationDate(value);
                patched.add("deregistrationDate");
            }
        }

        // 소유자 ID (약한 필드 - 16/33)
        if (isBlankOrInvalid(reg.getOwnerId(), this::isValidOwnerId)) {
            String value = normalizeOwnerId(rawField(json, "ownerId"));
            if (value != null && isValidOwnerId(value)) {
                reg.setOwnerId(value);
                patched.add("ownerId");
            }
        }

        // 소유자명 (약한 필드 - 24/33)
        if (isBlank(reg.getOwnerName())) {
            String value = normalizeOwnerName(rawField(json, "ownerName"));
            if (value != null && !value.isBlank()) {
                reg.setOwnerName(value);
                patched.add("ownerName");
            }
        }

        // 압류 건수 (약한 필드 - 23/33)
        if (isBlankOrInvalid(reg.getSeizureCount(), this::isValidCount)) {
            String value = normalizeCount(rawField(json, "seizureCount"));
            if (value != null && isValidCount(value)) {
                reg.setSeizureCount(value);
                patched.add("seizureCount");
            }
        }

        // 저당권 건수
        if (isBlankOrInvalid(reg.getMortgageCount(), this::isValidCount)) {
            String value = normalizeCount(rawField(json, "mortgageCount"));
            if (value != null && isValidCount(value)) {
                reg.setMortgageCount(value);
                patched.add("mortgageCount");
            }
        }

        // 원동기형식
        if (isBlankOrInvalid(reg.getEngineType(), this::isValidEngineType)) {
            String value = normalizeEngineType(rawField(json, "engineType"));
            if (value != null && isValidEngineType(value)) {
                reg.setEngineType(value);
                patched.add("engineType");
            }
        }

        // 발행일
        if (isBlankOrInvalid(reg.getIssueDate(), this::isValidDate)) {
            String value = normalizeDate(rawField(json, "issueDate"));
            if (value != null && isValidDate(value)) {
                reg.setIssueDate(value);
                patched.add("issueDate");
            }
        }

        // 발행기관
        if (isBlank(reg.getIssuer())) {
            String value = normalizeIssuer(rawField(json, "issuer"));
            if (value != null && !value.isBlank()) {
                reg.setIssuer(value);
                patched.add("issuer");
            }
        }

        // 말소사유
        if (isBlank(reg.getDeregistrationReason())) {
            String value = rawField(json, "deregistrationReason");
            if (value != null && !value.isBlank()) {
                reg.setDeregistrationReason(value);
                patched.add("deregistrationReason");
            }
        }

        // 모델연도
        if (reg.getModelYear() == null) {
            Integer year = parseModelYear(rawField(json, "modelYear"));
            if (year != null) {
                reg.setModelYear(year);
                patched.add("modelYear");
            }
        }

        // 차명
        if (isBlank(reg.getModelName())) {
            String value = rawField(json, "modelName");
            if (value != null && !value.isBlank() && value.length() < 30) {
                reg.setModelName(value);
                patched.add("modelName");
            }
        }

        // 차종
        if (isBlank(reg.getCarType())) {
            String value = rawField(json, "carType");
            if (value != null && isValidCarType(value)) {
                reg.setCarType(value);
                patched.add("carType");
            }
        }

        // 용도
        if (isBlank(reg.getVehicleUse())) {
            String value = rawField(json, "vehicleUse");
            if (value != null && (value.contains("자가용") || value.contains("사업용"))) {
                reg.setVehicleUse(value);
                patched.add("vehicleUse");
            }
        }

        return patched.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 말소증 핵심 필드 충족 여부 판단.
     * vin + vehicleNo + 말소등록일 + (최초등록일 or 소유자) 중 3개 이상이면 OK
     */
    private boolean isCoreValid(VehicleDeregistration reg) {
        return isValidVin(reg.getVin())
                && isValidPlate(reg.getVehicleNo())
                && isValidDate(reg.getDeregistrationDate())
                && (isValidDate(reg.getFirstRegistratedAt()) || !isBlank(reg.getOwnerName()));
    }

    // ═══ 정규화 ═══

    private String normalizePlate(String raw) {
        if (raw == null) return null;
        String compact = raw.replaceAll("\\s+", "").replaceAll("[^0-9가-힣]", "");
        Matcher m = PLATE.matcher(compact);
        return m.find() ? m.group() : null;
    }

    private String normalizeVin(String raw) {
        if (raw == null) return null;
        String compact = raw.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "")
                .replace('I', '1')
                .replace('O', '0')
                .replace('Q', '0');

        Set<String> variants = new LinkedHashSet<>();
        variants.add(compact);
        // B↔8 혼동 처리
        addCharSwapVariants(variants, compact, 'B', '8', 6, 256);

        for (String variant : variants) {
            Matcher m = VIN.matcher(variant);
            while (m.find()) {
                String vin = m.group();
                if (isValidVin(vin)) return vin;
            }
        }
        return null;
    }

    private String normalizeDate(String raw) {
        if (raw == null) return null;
        String norm = raw
                .replace('.', '-').replace('/', '-')
                .replace('년', '-').replace("월", "-").replace("일", "")
                .replaceAll("\\s+", "");
        Matcher m = DATE_YMD.matcher(norm);
        if (!m.find()) return null;
        String date = String.format(Locale.ROOT, "%s-%02d-%02d",
                m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        return isValidDate(date) ? date : null;
    }

    private String normalizeOwnerId(String raw) {
        if (raw == null) return null;
        String compact = raw.replaceAll("\\s+", "");
        Matcher m = OWNER_ID.matcher(compact);
        if (m.find()) {
            String prefix = m.group(1);
            String suffix = normalizeOwnerIdSuffix(m.group(2));
            String out = prefix + "-" + suffix;
            return isValidOwnerId(out) ? out : null;
        }
        // 13자리 연속
        String digits = compact.replaceAll("[^0-9*Xx]", "").toUpperCase(Locale.ROOT);
        if (digits.length() == 13) {
            String out = digits.substring(0, 6) + "-" + normalizeOwnerIdSuffix(digits.substring(6));
            return isValidOwnerId(out) ? out : null;
        }
        return null;
    }

    private String normalizeOwnerIdSuffix(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 'X' || c == 'x') sb.append('*');
            else if (Character.isDigit(c) || c == '*') sb.append(c);
        }
        String out = sb.toString();
        return out.length() > 7 ? out.substring(0, 7) : out;
    }

    private String normalizeOwnerName(String raw) {
        if (raw == null) return null;
        // "(상품용)" 제거
        String cleaned = raw.replaceAll("[*+]?\\(?상품용\\)?[*+]?", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalizeCount(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        // 앞자리 0 제거
        int value = Integer.parseInt(digits);
        return String.valueOf(value);
    }

    private String normalizeEngineType(String raw) {
        if (raw == null) return null;
        String compact = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        compact = compact.replaceAll("[^A-Z0-9\\-_/\\.]", "");
        return compact.isBlank() ? null : compact;
    }

    private String normalizeIssuer(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("\\s+", "").trim();
    }

    private Integer parseModelYear(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 4) return null;
        int year = Integer.parseInt(digits.substring(0, 4));
        return (year >= 1990 && year <= LocalDate.now().getYear() + 1) ? year : null;
    }

    // ═══ 검증 ═══

    private boolean isValidPlate(String plate) {
        return plate != null && PLATE.matcher(plate).matches();
    }

    private boolean isValidVin(String vin) {
        if (vin == null || vin.length() != 17) return false;
        if (!VIN.matcher(vin).matches()) return false;
        int alpha = 0, digit = 0;
        for (char c : vin.toCharArray()) {
            if (Character.isLetter(c)) alpha++;
            if (Character.isDigit(c)) digit++;
        }
        return alpha >= 3 && digit >= 5;
    }

    private boolean isValidDate(String date) {
        if (date == null) return false;
        try {
            LocalDate parsed = LocalDate.parse(date);
            LocalDate min = LocalDate.of(1980, 1, 1);
            LocalDate max = LocalDate.now().plusDays(1);
            return !parsed.isBefore(min) && !parsed.isAfter(max);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean isValidOwnerId(String id) {
        return id != null && OWNER_ID_CANONICAL.matcher(id).matches();
    }

    private boolean isValidCount(String value) {
        return value != null && COUNT_PATTERN.matcher(value).matches();
    }

    private boolean isValidEngineType(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.length() < 2 || value.length() > 20) return false;
        return value.matches("[A-Z0-9\\-_/\\.]+");
    }

    private boolean isValidCarType(String value) {
        if (value == null) return false;
        return value.matches(".*(승용|승합|화물|특수|이륜).*");
    }

    // ═══ 유틸 ═══

    private String buildPageText(List<OcrWord> words) {
        if (words == null || words.isEmpty()) return null;
        return LineGrouper.group(words, lineYThresholdRatio).stream()
                .map(line -> line.fullText() == null ? "" : line.fullText().trim())
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String rawField(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String text = v.asText();
        if (text == null) return null;
        text = text.trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private void appendReason(VehicleDeregistration reg, String note) {
        String current = reg.getQualityReason();
        if (current == null || current.isBlank() || "OK".equalsIgnoreCase(current)) {
            reg.setQualityReason(note);
            return;
        }
        if (!current.contains(note)) {
            reg.setQualityReason(current + " | " + note);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void addCharSwapVariants(Set<String> variants, String src, char a, char b, int maxSwap, int maxVariants) {
        if (src == null || src.isBlank() || maxSwap <= 0 || maxVariants <= 0) return;
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == a || c == b) positions.add(i);
        }
        if (positions.isEmpty()) return;
        char[] chars = src.toCharArray();
        buildSwapVariants(variants, chars, positions, 0, 0, maxSwap, maxVariants, a, b);
    }

    private void buildSwapVariants(Set<String> variants, char[] chars, List<Integer> positions,
                                   int idx, int swapCount, int maxSwap, int maxVariants, char a, char b) {
        if (variants.size() >= maxVariants) return;
        if (idx >= positions.size()) {
            if (swapCount > 0) variants.add(new String(chars));
            return;
        }
        buildSwapVariants(variants, chars, positions, idx + 1, swapCount, maxSwap, maxVariants, a, b);
        if (variants.size() >= maxVariants || swapCount >= maxSwap) return;
        int pos = positions.get(idx);
        char original = chars[pos];
        chars[pos] = (original == a) ? b : a;
        buildSwapVariants(variants, chars, positions, idx + 1, swapCount + 1, maxSwap, maxVariants, a, b);
        chars[pos] = original;
    }
}
