package exps.cariv.domain.clova.layout;

import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.TextLine;
import exps.cariv.domain.clova.parser.LineGrouper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OCR raw word를 parser 친화적인 정규화 레이아웃으로 변환.
 */
@Component
public class LayoutNormalizer {

    @Value("${ocr.parser.line-y-threshold-ratio:0.5}")
    private double lineYThresholdRatio = 0.5;

    public NormalizedLayout normalize(List<OcrWord> words) {
        if (words == null || words.isEmpty()) {
            return new NormalizedLayout(1, 1, new ArrayList<>(), new ArrayList<>());
        }

        double maxX = 0;
        double maxY = 0;
        for (OcrWord w : words) {
            maxX = Math.max(maxX, w.rightX());
            maxY = Math.max(maxY, w.bottomY());
        }
        double docWidth = Math.max(1, maxX);
        double docHeight = Math.max(1, maxY);

        List<NormalizedToken> tokens = new ArrayList<>(words.size());
        Map<OcrWord, NormalizedToken> byRaw = new IdentityHashMap<>();
        for (OcrWord w : words) {
            NormalizedToken token = new NormalizedToken(
                    w.getText(),
                    TextNormalizer.normalizeForMatch(w.getText()),
                    w.getConfidence(),
                    w.isLineBreak(),
                    w.getX(),
                    w.getY(),
                    w.getWidth(),
                    w.getHeight(),
                    w.getX() / docWidth,
                    w.getY() / docHeight,
                    w.getWidth() / docWidth,
                    w.getHeight() / docHeight,
                    w
            );
            tokens.add(token);
            byRaw.put(w, token);
        }

        List<TextLine> grouped = LineGrouper.group(words, lineYThresholdRatio);
        List<NormalizedLine> lines = new ArrayList<>(grouped.size());
        for (TextLine line : grouped) {
            List<NormalizedToken> lineTokens = line.getWords().stream()
                    .map(byRaw::get)
                    .filter(t -> t != null)
                    .sorted(Comparator.comparingDouble(NormalizedToken::getX))
                    .collect(Collectors.toList());

            String text = lineTokens.stream()
                    .map(NormalizedToken::getText)
                    .collect(Collectors.joining(" "))
                    .trim();
            String normalizedText = lineTokens.stream()
                    .map(NormalizedToken::getNormalizedText)
                    .collect(Collectors.joining());

            lines.add(new NormalizedLine(lineTokens, text, normalizedText, line.avgY()));
        }

        return new NormalizedLayout(docWidth, docHeight, tokens, lines);
    }
}
