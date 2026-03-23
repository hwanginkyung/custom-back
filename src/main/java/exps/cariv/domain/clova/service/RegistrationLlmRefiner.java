package exps.cariv.domain.clova.service;

import exps.cariv.domain.clova.client.LlmFieldExtractor;
import exps.cariv.domain.clova.dto.FieldEvidence;
import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.parser.LineGrouper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 자동차등록증 룰 파서 결과를 LLM으로 선택 보완한다.
 * - 룰 기반 파서를 항상 우선 사용
 * - 낮은 confidence / 검증 실패 필드만 LLM 결과로 교체
 */
@Slf4j
@Component
public class RegistrationLlmRefiner {

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
    private static final Pattern YEAR_MONTH = Pattern.compile("(?<!\\d)(\\d{4})\\D*(\\d{1,2})(?!\\d)");
    private static final Pattern OWNER_ID = Pattern.compile("(\\d{6})\\s*[-:]?\\s*([\\d*Xx]{7})");
    private static final Pattern OWNER_ID_CANONICAL = Pattern.compile("^\\d{6}-[\\d*]{7}$");
    private static final Pattern KG_NUMBER = Pattern.compile("(\\d{2,5})");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Value("${app.ocr.llm.enabled:false}")
    private boolean enabled;

    @Value("${app.ocr.llm.provider:none}")
    private String configuredProvider;

    @Value("${app.ocr.llm.min-field-confidence:0.68}")
    private double minFieldConfidence;

    @Value("${app.ocr.llm.max-text-chars:12000}")
    private int maxTextChars;

    @Value("${app.ocr.llm.claude-api-key:}")
    private String claudeApiKey;

    @Value("${app.ocr.llm.openai-api-key:}")
    private String openaiApiKey;

    @Value("${app.ocr.parser.line-y-threshold-ratio:0.5}")
    private double lineYThresholdRatio = 0.5;

    public VehicleRegistration refineIfNeeded(
            VehicleRegistration base,
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
                    ? extractor.extractWithClaude(pageText, "registration")
                    : extractor.extractWithGpt(pageText, "registration");
            JsonNode json = extractor.parseResponse(raw);
            if (json == null || !json.isObject()) return base;

            List<String> patchedFields = patchFields(base, json, provider);
            if (!patchedFields.isEmpty()) {
                appendReason(base, String.format(
                        Locale.ROOT,
                        "LLM(%s) 보완 적용 fields=%s ocr=%s file=%s",
                        provider.name().toLowerCase(Locale.ROOT),
                        String.join(",", patchedFields),
                        ocrProvider,
                        fileName
                ));
                if (isCoreValid(base)) {
                    base.setNeedsRetry(false);
                }
            }
        } catch (Exception e) {
            log.warn("LLM refine skipped due to error: {}", e.getMessage());
        }
        return base;
    }

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

    private boolean shouldRun(VehicleRegistration reg) {
        if (Boolean.TRUE.equals(reg.getNeedsRetry())) return true;

        return needsRefresh(reg, "vin", reg.getVin(), this::passesVinQuality)
                || needsRefresh(reg, "vehicleNo", reg.getVehicleNo(), this::isValidPlate)
                || needsRefresh(reg, "firstRegistratedAt", reg.getFirstRegistratedAt(), this::isValidDate)
                || needsRefresh(reg, "manufactureYearMonth", reg.getManufactureYearMonth(), this::isValidYearMonth)
                || needsRefresh(reg, "ownerId", reg.getOwnerId(), this::isValidOwnerId)
                || needsRefresh(reg, "engineType", reg.getEngineType(), this::isValidEngineType)
                || needsRefresh(reg, "weight", reg.getWeight(), this::isValidKg)
                || isBlank(reg.getMaxLoad());
    }

    private boolean needsRefresh(VehicleRegistration reg, String field, String value, Predicate<String> validator) {
        if (value == null || value.isBlank()) return true;
        if (!validator.test(value)) return true;
        Double conf = fieldConfidence(reg, field);
        return conf == null || conf < minFieldConfidence;
    }

    /**
     * confidence 무시, blank이거나 validator 실패일 때만 true.
     * 규칙 파서가 더 강한 필드(maxLoad 등)에 사용.
     */
    private boolean needsBlankOrInvalid(String value, Predicate<String> validator) {
        if (value == null || value.isBlank()) return true;
        return !validator.test(value);
    }

    private Double fieldConfidence(VehicleRegistration reg, String field) {
        Map<String, FieldEvidence> ev = reg.getEvidence();
        if (ev == null) return null;
        FieldEvidence item = ev.get(field);
        if (item == null) return null;
        return item.getConfidence();
    }

    private List<String> patchFields(VehicleRegistration reg, JsonNode json, Provider provider) {
        List<String> patched = new ArrayList<>();

        if (needsRefresh(reg, "vin", reg.getVin(), this::passesVinQuality)) {
            String value = normalizeVin(rawField(json, "vin"));
            if (value != null && passesVinQuality(value)) {
                reg.setVin(value);
                putLlmEvidence(reg, "vin", value, value, vinConfidence(value), provider);
                patched.add("vin");
            }
        }

        if (needsRefresh(reg, "vehicleNo", reg.getVehicleNo(), this::isValidPlate)) {
            String value = normalizePlate(rawField(json, "vehicleNo"));
            if (value != null && isValidPlate(value)) {
                reg.setVehicleNo(value);
                putLlmEvidence(reg, "vehicleNo", value, value, 0.84, provider);
                patched.add("vehicleNo");
            }
        }

        if (needsRefresh(reg, "firstRegistratedAt", reg.getFirstRegistratedAt(), this::isValidDate)) {
            String raw = rawField(json, "firstRegistratedAt");
            String value = normalizeDate(raw);
            if (value != null && isValidDate(value)) {
                reg.setFirstRegistratedAt(value);
                putLlmEvidence(reg, "firstRegistratedAt", value, raw, 0.82, provider);
                patched.add("firstRegistratedAt");
            }
        }

        if (needsRefresh(reg, "manufactureYearMonth", reg.getManufactureYearMonth(), this::isValidYearMonth)) {
            String raw = rawField(json, "manufactureYearMonth");
            String value = normalizeYearMonth(raw);
            if (value != null && isValidYearMonth(value)) {
                reg.setManufactureYearMonth(value);
                putLlmEvidence(reg, "manufactureYearMonth", value, raw, 0.80, provider);
                patched.add("manufactureYearMonth");
                if (reg.getModelYear() == null || !isValidModelYear(reg.getModelYear())) {
                    reg.setModelYear(Integer.parseInt(value.substring(0, 4)));
                }
            }
        }

        if (reg.getModelYear() == null || !isValidModelYear(reg.getModelYear())) {
            Integer modelYear = parseModelYear(rawField(json, "modelYear"));
            if (isValidModelYear(modelYear)) {
                reg.setModelYear(modelYear);
                putLlmEvidence(reg, "modelYear", String.valueOf(modelYear), rawField(json, "modelYear"), 0.78, provider);
                patched.add("modelYear");
            }
        }

        if (needsRefresh(reg, "ownerId", reg.getOwnerId(), this::isValidOwnerId)) {
            String raw = rawField(json, "ownerId");
            String value = normalizeOwnerId(raw);
            if (value != null && isValidOwnerId(value)) {
                reg.setOwnerId(value);
                putLlmEvidence(reg, "ownerId", value, raw, 0.76, provider);
                patched.add("ownerId");
            }
        }

        if (needsRefresh(reg, "engineType", reg.getEngineType(), this::isValidEngineType)) {
            String raw = rawField(json, "engineType");
            String value = normalizeEngineType(raw);
            if (value != null && isValidEngineType(value)) {
                reg.setEngineType(value);
                putLlmEvidence(reg, "engineType", value, raw, 0.72, provider);
                patched.add("engineType");
            }
        }

        String currentWeight = reg.getWeight();
        Integer referenceWeight = parseKgInt(currentWeight);
        if (needsRefresh(reg, "weight", currentWeight, this::isValidKg)) {
            String raw = rawField(json, "weight");
            String value = normalizeKg(raw);
            if (value != null && isValidKg(value)) {
                reg.setWeight(value);
                referenceWeight = parseKgInt(value);
                putLlmEvidence(reg, "weight", value, raw, 0.74, provider);
                patched.add("weight");
            }
        }

        if (isBlank(reg.getMaxLoad())) {
            String raw = rawField(json, "maxLoad");
            String value = normalizeKg(raw);
            Integer parsed = parseKgInt(value);
            if (parsed != null && referenceWeight != null && parsed > referenceWeight) {
                value = referenceWeight + "kg";
            }
            if (value != null && isValidKg(value)) {
                reg.setMaxLoad(value);
                putLlmEvidence(reg, "maxLoad", value, raw, 0.72, provider);
                patched.add("maxLoad");
            }
        }

        return patched.stream().distinct().collect(Collectors.toList());
    }

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
        String text = v.isTextual() ? v.asText() : v.asText();
        if (text == null) return null;
        text = text.trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private void putLlmEvidence(
            VehicleRegistration reg,
            String field,
            String value,
            String rawValue,
            double confidence,
            Provider provider
    ) {
        Map<String, FieldEvidence> map = reg.getEvidence();
        if (map == null) {
            map = new LinkedHashMap<>();
            reg.setEvidence(map);
        }
        String raw = rawValue == null ? value : rawValue;
        map.put(field, new FieldEvidence(
                value,
                round4(confidence),
                "LLM:" + provider.name().toLowerCase(Locale.ROOT),
                null,
                raw,
                null,
                List.of("llm_raw=" + raw)
        ));
    }

    private void appendReason(VehicleRegistration reg, String note) {
        String current = reg.getQualityReason();
        if (current == null || current.isBlank() || "OK".equalsIgnoreCase(current)) {
            reg.setQualityReason(note);
            return;
        }
        if (!current.contains(note)) {
            reg.setQualityReason(current + " | " + note);
        }
    }

    private boolean isCoreValid(VehicleRegistration reg) {
        boolean modelOk = isValidYearMonth(reg.getManufactureYearMonth()) || isValidModelYear(reg.getModelYear());
        return isValidPlate(reg.getVehicleNo())
                && passesVinQuality(reg.getVin())
                && isValidDate(reg.getFirstRegistratedAt())
                && modelOk;
    }

    private String normalizePlate(String raw) {
        if (raw == null) return null;
        String compact = raw.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9A-Z가-힣]", "");

        int hangulIdx = -1;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c >= '가' && c <= '힣') {
                hangulIdx = i;
                break;
            }
        }

        String candidate;
        if (hangulIdx >= 0) {
            String prefix = normalizeNumericOnlyText(compact.substring(0, hangulIdx));
            String suffix = normalizeNumericOnlyText(compact.substring(hangulIdx + 1));
            candidate = prefix + compact.charAt(hangulIdx) + suffix;
        } else {
            candidate = normalizeNumericOnlyText(compact);
        }

        Matcher m = PLATE.matcher(candidate);
        return m.find() ? m.group() : null;
    }

    private boolean isValidPlate(String plate) {
        return plate != null && PLATE.matcher(plate).matches();
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
        addCharSwapVariants(variants, compact, 'B', '8', 6, 256);

        Set<String> all = new LinkedHashSet<>();
        for (String variant : variants) {
            Matcher m = VIN.matcher(variant);
            while (m.find()) {
                all.add(m.group());
            }
        }

        return all.stream()
                .sorted(Comparator.comparingInt(this::vinSortScore).reversed())
                .findFirst()
                .orElse(null);
    }

    private int vinSortScore(String vin) {
        if (vin == null || vin.length() != 17) return Integer.MIN_VALUE / 4;
        int score = 0;
        if (passesVinQuality(vin)) score += 1000;
        if (isVinCheckDigitValid(vin)) score += isNorthAmericaVin(vin) ? 500 : 100;

        int tailDigits = 0;
        for (int i = 11; i < 17; i++) {
            if (Character.isDigit(vin.charAt(i))) tailDigits++;
        }
        score += tailDigits * 120;
        score -= (6 - tailDigits) * 150;
        return score;
    }

    private boolean passesVinQuality(String vin) {
        if (!isValidVin(vin)) return false;
        if (isNorthAmericaVin(vin)) {
            return isVinCheckDigitValid(vin);
        }
        int alphaCount = 0;
        int digitCount = 0;
        for (int i = 0; i < vin.length(); i++) {
            char c = vin.charAt(i);
            if (Character.isLetter(c)) alphaCount++;
            if (Character.isDigit(c)) digitCount++;
        }
        int tailDigits = 0;
        for (int i = 11; i < 17; i++) {
            if (Character.isDigit(vin.charAt(i))) tailDigits++;
        }
        return alphaCount >= 3 && digitCount >= 5 && tailDigits >= 4;
    }

    private boolean isValidVin(String vin) {
        return vin != null && VIN.matcher(vin).matches();
    }

    private boolean isNorthAmericaVin(String vin) {
        if (vin == null || vin.isEmpty()) return false;
        char c = vin.charAt(0);
        return c >= '1' && c <= '5';
    }

    private double vinConfidence(String vin) {
        if (vin == null) return 0.0;
        if (isNorthAmericaVin(vin) && isVinCheckDigitValid(vin)) return 0.88;
        return passesVinQuality(vin) ? 0.78 : 0.0;
    }

    private boolean isVinCheckDigitValid(String vin) {
        if (vin == null || vin.length() != 17 || !VIN.matcher(vin).matches()) return false;
        char checkChar = vin.charAt(8);
        if (!Character.isDigit(checkChar) && checkChar != 'X') return false;

        int[] weights = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < vin.length(); i++) {
            int value = vinCharValue(vin.charAt(i));
            if (value < 0) return false;
            sum += value * weights[i];
        }
        int remainder = sum % 11;
        char expected = remainder == 10 ? 'X' : (char) ('0' + remainder);
        return checkChar == expected;
    }

    private int vinCharValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        return switch (c) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Y' -> 8;
            case 'R', 'Z' -> 9;
            default -> -1;
        };
    }

    private String normalizeDate(String raw) {
        if (raw == null) return null;
        String norm = normalizeNumericOnlyText(raw)
                .replace('.', '-')
                .replace('/', '-')
                .replace('년', '-')
                .replace("월", "-")
                .replace("일", "")
                .replaceAll("\\s+", "");
        Matcher m = DATE_YMD.matcher(norm);
        if (!m.find()) return null;
        String date = String.format(
                Locale.ROOT,
                "%s-%02d-%02d",
                m.group(1),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
        );
        return isValidDate(date) ? date : null;
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

    private String normalizeYearMonth(String raw) {
        if (raw == null) return null;
        String norm = normalizeNumericOnlyText(raw)
                .replace('.', '-')
                .replace('/', '-')
                .replace('년', '-')
                .replace("월", "")
                .replaceAll("\\s+", "");
        Matcher m = YEAR_MONTH.matcher(norm);
        if (!m.find()) return null;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        String ym = String.format(Locale.ROOT, "%04d-%02d", year, month);
        return isValidYearMonth(ym) ? ym : null;
    }

    private boolean isValidYearMonth(String ym) {
        if (ym == null || !ym.matches("^\\d{4}-\\d{2}$")) return false;
        try {
            YearMonth value = YearMonth.parse(ym, YEAR_MONTH_FMT);
            YearMonth min = YearMonth.of(1980, 1);
            YearMonth max = YearMonth.now().plusMonths(1);
            return !value.isBefore(min) && !value.isAfter(max);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Integer parseModelYear(String raw) {
        if (raw == null) return null;
        String digits = normalizeNumericOnlyText(raw).replaceAll("[^0-9]", "");
        if (digits.length() < 4) return null;
        Integer year = Integer.parseInt(digits.substring(0, 4));
        return isValidModelYear(year) ? year : null;
    }

    private boolean isValidModelYear(Integer year) {
        if (year == null) return false;
        int min = 1980;
        int max = LocalDate.now().getYear() + 1;
        return year >= min && year <= max;
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
        if (out.length() < 7) return out;
        return out.substring(0, 7);
    }

    private boolean isValidOwnerId(String ownerId) {
        return ownerId != null && OWNER_ID_CANONICAL.matcher(ownerId).matches();
    }

    private String normalizeEngineType(String raw) {
        if (raw == null) return null;
        String compact = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        compact = compact.replaceAll("[^A-Z0-9\\-_/\\.]", "");
        if (compact.isBlank()) return null;
        return compact;
    }

    private boolean isValidEngineType(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.length() < 2 || value.length() > 20) return false;
        return value.matches("[A-Z0-9\\-_/\\.]+");
    }

    private String normalizeKg(String raw) {
        if (raw == null) return null;
        String normalized = normalizeNumericOnlyText(raw).replace(",", "");
        Matcher m = KG_NUMBER.matcher(normalized);
        while (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (value >= 50 && value <= 50000) {
                return value + "kg";
            }
        }
        return null;
    }

    private boolean isValidKg(String value) {
        Integer parsed = parseKgInt(value);
        return parsed != null && parsed >= 50 && parsed <= 50000;
    }

    private Integer parseKgInt(String value) {
        if (value == null) return null;
        Matcher m = KG_NUMBER.matcher(normalizeNumericOnlyText(value));
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 숫자 문맥(차량번호 숫자부/날짜/kg 등)에서만 쓰는 OCR 혼동 치환.
     * 엔진코드 같은 알파벳 코드에는 적용하지 않는다.
     */
    private String normalizeNumericOnlyText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            sb.append(swapNumericLikeCharNumericOnly(c));
        }
        return sb.toString();
    }

    private char swapNumericLikeCharNumericOnly(char c) {
        return switch (c) {
            case 'O', 'Q', 'D' -> '0';
            case 'I', 'L' -> '1';
            case 'Z' -> '2';
            case 'S' -> '5';
            case 'G' -> '6';
            case 'B' -> '8';
            default -> c;
        };
    }

    private void addCharSwapVariants(
            Set<String> variants,
            String src,
            char a,
            char b,
            int maxSwapCount,
            int maxVariants
    ) {
        if (src == null || src.isBlank() || maxSwapCount <= 0 || maxVariants <= 0) return;
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == a || c == b) positions.add(i);
        }
        if (positions.isEmpty()) return;

        char[] chars = src.toCharArray();
        buildSwapVariants(variants, chars, positions, 0, 0, maxSwapCount, maxVariants, a, b);
    }

    private void buildSwapVariants(
            Set<String> variants,
            char[] chars,
            List<Integer> positions,
            int idx,
            int swapCount,
            int maxSwapCount,
            int maxVariants,
            char a,
            char b
    ) {
        if (variants.size() >= maxVariants) return;
        if (idx >= positions.size()) {
            if (swapCount > 0) variants.add(new String(chars));
            return;
        }

        buildSwapVariants(variants, chars, positions, idx + 1, swapCount, maxSwapCount, maxVariants, a, b);
        if (variants.size() >= maxVariants || swapCount >= maxSwapCount) return;

        int pos = positions.get(idx);
        char original = chars[pos];
        chars[pos] = (original == a) ? b : a;
        buildSwapVariants(variants, chars, positions, idx + 1, swapCount + 1, maxSwapCount, maxVariants, a, b);
        chars[pos] = original;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
