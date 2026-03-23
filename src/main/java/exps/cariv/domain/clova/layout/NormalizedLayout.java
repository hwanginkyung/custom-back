package exps.cariv.domain.clova.layout;

import exps.cariv.domain.clova.dto.OcrWord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Raw OCR -> Normalized Layout 중간 추상화 계층.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedLayout {
    private double docWidth;
    private double docHeight;
    private List<NormalizedToken> tokens = new ArrayList<>();
    private List<NormalizedLine> lines = new ArrayList<>();

    public List<OcrWord> toOcrWords() {
        return tokens.stream()
                .map(NormalizedToken::getRawWord)
                .collect(Collectors.toList());
    }
}
