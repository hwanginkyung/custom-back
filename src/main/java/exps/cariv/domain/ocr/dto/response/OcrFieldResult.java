package exps.cariv.domain.ocr.dto.response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR 필드 단위 결과 응답.
 */
public record OcrFieldResult(
        Map<String, String> values,
        Map<String, String> invalidFields,
        List<String> missingFields,
        Summary summary
) {
    public record Summary(
            int successCount,
            int errorCount,
            int missingCount
    ) {}

    public static OcrFieldResult empty() {
        return new OcrFieldResult(
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(),
                new Summary(0, 0, 0)
        );
    }
}
