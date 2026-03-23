package exps.cariv.domain.clova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OCR 결과의 개별 단어 (bounding box + text)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrWord {
    private String text;
    private double x;      // 좌상단 x
    private double y;      // 좌상단 y
    private double width;  // bbox 너비
    private double height; // bbox 높이
    private double confidence;
    private boolean lineBreak; // CLOVA OCR의 lineBreak 필드

    /** bbox 중심 Y */
    public double centerY() {
        return y + height / 2.0;
    }

    /** bbox 중심 X */
    public double centerX() {
        return x + width / 2.0;
    }

    /** bbox 오른쪽 끝 */
    public double rightX() {
        return x + width;
    }

    /** bbox 아래쪽 끝 */
    public double bottomY() {
        return y + height;
    }
}
