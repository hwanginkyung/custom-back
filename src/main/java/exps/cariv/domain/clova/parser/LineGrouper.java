package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.TextLine;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OCR bbox → 줄(line) 묶기
 * Y좌표가 비슷한 단어들을 같은 줄로 그룹핑
 */
@Slf4j
public class LineGrouper {

    /**
     * OcrWord 리스트를 줄 단위로 그룹핑하여 Y순 정렬된 TextLine 리스트 반환
     *
     * @param words OCR 인식 결과 단어 리스트
     * @param yThresholdRatio 같은 줄로 판단할 Y 거리 비율 (평균 높이 대비). 기본 0.5
     * @return Y순 정렬된 TextLine 리스트
     */
    public static List<TextLine> group(List<OcrWord> words, double yThresholdRatio) {
        if (words.isEmpty()) return Collections.emptyList();

        // 평균 글자 높이 계산
        double avgH = words.stream().mapToDouble(OcrWord::getHeight).average().orElse(20);
        double yThreshold = avgH * yThresholdRatio;

        // centerY 기준 정렬
        List<OcrWord> sorted = words.stream()
                .sorted(Comparator.comparingDouble(OcrWord::centerY))
                .collect(Collectors.toList());

        List<TextLine> lines = new ArrayList<>();
        TextLine currentLine = new TextLine();
        currentLine.addWord(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            OcrWord word = sorted.get(i);
            double currentLineAvgY = currentLine.avgY();

            if (Math.abs(word.centerY() - currentLineAvgY) <= yThreshold) {
                // 같은 줄
                currentLine.addWord(word);
            } else {
                // 새 줄
                lines.add(currentLine);
                currentLine = new TextLine();
                currentLine.addWord(word);
            }
        }
        lines.add(currentLine);

        // Y순 정렬
        lines.sort(Comparator.comparingDouble(TextLine::avgY));

        log.debug("Grouped {} words into {} lines", words.size(), lines.size());
        return lines;
    }

    public static List<TextLine> group(List<OcrWord> words) {
        return group(words, 0.5);
    }
}
