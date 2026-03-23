package exps.cariv.domain.clova.layout;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 정규화된 라인 단위 레이아웃.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedLine {
    private List<NormalizedToken> tokens = new ArrayList<>();
    private String text;
    private String normalizedText;
    private double avgY;
}
