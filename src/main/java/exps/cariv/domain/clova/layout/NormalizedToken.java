package exps.cariv.domain.clova.layout;

import exps.cariv.domain.clova.dto.OcrWord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OCR raw word를 문서 기준 좌표로 정규화한 토큰.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedToken {
    private String text;
    private String normalizedText;
    private double confidence;
    private boolean lineBreak;

    private double x;
    private double y;
    private double width;
    private double height;

    private double nx;
    private double ny;
    private double nWidth;
    private double nHeight;

    private OcrWord rawWord;
}
