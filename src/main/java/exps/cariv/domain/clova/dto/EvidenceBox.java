package exps.cariv.domain.clova.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OCR bbox evidence.
 * Absolute and normalized coordinates are both included.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceBox {
    private double x;
    private double y;
    private double width;
    private double height;
    private double nx;
    private double ny;
    private double nWidth;
    private double nHeight;
}
