package exps.cariv.domain.clova.client;

import exps.cariv.domain.clova.dto.OcrWord;

import java.io.IOException;
import java.util.List;

/**
 * OCR 엔진 추상화.
 */
public interface OcrClient {

    List<OcrWord> recognize(byte[] imageBytes, String fileName) throws IOException;

    default String provider() {
        return "unknown";
    }
}
