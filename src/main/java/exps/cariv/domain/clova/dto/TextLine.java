package exps.cariv.domain.clova.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 같은 줄로 묶인 OcrWord 리스트
 */
@Data
public class TextLine {
    private final List<OcrWord> words = new ArrayList<>();

    public void addWord(OcrWord word) {
        words.add(word);
    }

    /** 줄의 평균 Y 좌표 */
    public double avgY() {
        return words.stream().mapToDouble(OcrWord::centerY).average().orElse(0);
    }

    /** 줄의 최소 X */
    public double minX() {
        return words.stream().mapToDouble(OcrWord::getX).min().orElse(0);
    }

    /** 줄의 최대 X (오른쪽 끝) */
    public double maxX() {
        return words.stream().mapToDouble(OcrWord::rightX).max().orElse(0);
    }

    /** 줄의 최소 Y */
    public double minY() {
        return words.stream().mapToDouble(OcrWord::getY).min().orElse(0);
    }

    /** 줄의 평균 높이 */
    public double avgHeight() {
        return words.stream().mapToDouble(OcrWord::getHeight).average().orElse(0);
    }

    /** X순 정렬된 전체 텍스트 */
    public String fullText() {
        return words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .map(OcrWord::getText)
                .collect(Collectors.joining(" "));
    }

    /** 공백 없이 합친 텍스트 */
    public String compactText() {
        return words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .map(OcrWord::getText)
                .collect(Collectors.joining());
    }

    /** 특정 x 이후의 단어들만 합친 텍스트 */
    public String textAfterX(double x) {
        return words.stream()
                .filter(w -> w.getX() > x)
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .map(OcrWord::getText)
                .collect(Collectors.joining(" "));
    }

    /** 특정 x 이후의 단어들만 합친 텍스트 (공백 없이) */
    public String compactTextAfterX(double x) {
        return words.stream()
                .filter(w -> w.getX() > x)
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .map(OcrWord::getText)
                .collect(Collectors.joining());
    }
}
