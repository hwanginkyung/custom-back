package exps.cariv.domain.ocr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OCR 파싱 결과 JSON(resultJson)을 화면 친화적인 구조로 정규화한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrResultNormalizer {

    private static final String INVALID_MESSAGE = "형식 오류";

    private final ObjectMapper objectMapper;

    public OcrFieldResult normalize(DocumentType documentType, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return OcrFieldResult.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(resultJson);

            Map<String, String> values = toCanonicalValues(documentType, root.path("parsed"));
            Set<String> invalidKeys = toCanonicalKeySet(documentType, root.path("errorFields"));
            Set<String> missingKeys = toCanonicalKeySet(documentType, root.path("missingFields"));

            Map<String, String> invalidFields = new LinkedHashMap<>();
            for (String key : invalidKeys) {
                invalidFields.put(key, INVALID_MESSAGE);
            }

            int successCount = 0;
            for (String key : values.keySet()) {
                if (!invalidKeys.contains(key) && !missingKeys.contains(key)) {
                    successCount++;
                }
            }

            return new OcrFieldResult(
                    values,
                    invalidFields,
                    new ArrayList<>(missingKeys),
                    new OcrFieldResult.Summary(successCount, invalidKeys.size(), missingKeys.size())
            );
        } catch (Exception e) {
            log.warn("[OcrResultNormalizer] failed to normalize resultJson: {}", e.getMessage());
            return OcrFieldResult.empty();
        }
    }

    private Map<String, String> toCanonicalValues(DocumentType documentType, JsonNode parsedNode) {
        Map<String, String> values = new LinkedHashMap<>();
        if (parsedNode == null || parsedNode.isMissingNode() || !parsedNode.isObject()) {
            return values;
        }

        parsedNode.fields().forEachRemaining(entry -> {
            String canonicalKey = mapKey(documentType, entry.getKey());
            String value = toScalarString(entry.getValue());
            if (value != null && !value.isBlank()) {
                values.putIfAbsent(canonicalKey, value);
            }
        });

        return values;
    }

    private Set<String> toCanonicalKeySet(DocumentType documentType, JsonNode arrayNode) {
        Set<String> keys = new LinkedHashSet<>();
        if (arrayNode == null || arrayNode.isMissingNode() || !arrayNode.isArray()) {
            return keys;
        }

        for (JsonNode node : arrayNode) {
            if (!node.isTextual()) continue;
            String key = node.asText();
            if (key == null || key.isBlank()) continue;
            keys.add(mapKey(documentType, key));
        }
        return keys;
    }

    private String toScalarString(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber() || node.isBoolean()) return node.asText();
        return null;
    }

    private String mapKey(DocumentType documentType, String key) {
        if (key == null) return null;
        return switch (documentType) {
            case AUCTION_CERTIFICATE -> switch (key) {
                case "registrationNo" -> "vehicleNo";
                case "chassisNo" -> "vin";
                case "model" -> "modelName";
                case "mileage" -> "mileageKm";
                case "initialRegistrationDate" -> "firstRegistratedAt";
                case "fuel" -> "fuelType";
                default -> key;
            };
            case CONTRACT -> switch (key) {
                case "registrationNo" -> "vehicleNo";
                case "vehicleType" -> "carType";
                case "model" -> "modelName";
                case "chassisNo" -> "vin";
                default -> key;
            };
            case DEREGISTRATION -> switch (key) {
                case "registrationNo" -> "vehicleNo";
                default -> key;
            };
            default -> key;
        };
    }
}
