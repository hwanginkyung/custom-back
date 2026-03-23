package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.TextLine;
import exps.cariv.domain.clova.layout.TextNormalizer;

import java.util.*;
import java.util.stream.Collectors;

/**
 * anchor 문자열 탐색기.
 * - 공백/특수문자 정규화
 * - alias 사전
 * - 유사 문자열 매칭(편집거리)
 */
public class AnchorResolver {

    private static final Map<String, List<String>> ALIAS = Map.ofEntries(
            Map.entry("자동차등록번호", List.of("자동차등록번호", "자동차등록 번 호", "자동차등륵번호", "자동차 등록번호")),
            Map.entry("차대번호", List.of("차대번호", "차대 번 호", "차대버호")),
            Map.entry("최초등록일", List.of("최초등록일", "최초 등록일", "최초등록")),
            Map.entry("형식및제작연월", List.of(
                    "형식및제작연월", "형식및모델연도", "형식및제작연도",
                    "형식밋제작연월", "형식밀제작연월",
                    "형식및연식", "형식및연식(모델연도)", "형식및연식(모델년도)"
            )),
            Map.entry("승차정원", List.of("승차정원", "정원")),
            Map.entry("배기량", List.of("배기량", "배기 량"))
    );

    private final List<OcrWord> allWords;
    private final List<TextLine> lines;

    public AnchorResolver(List<OcrWord> allWords, List<TextLine> lines) {
        this.allWords = allWords;
        this.lines = lines;
    }

    public OcrWord find(String anchor, double minY) {
        String target = normalize(anchor);
        Set<String> terms = candidateTerms(anchor);

        // 1) 단일 단어 exact
        for (OcrWord word : allWords) {
            if (minY > 0 && word.getY() < minY) continue;
            String cleaned = stripFieldNum(normalize(word.getText()));
            if (terms.contains(cleaned)) return word;
        }

        // 2) 단일 단어 contains / 유사도
        for (OcrWord word : allWords) {
            if (minY > 0 && word.getY() < minY) continue;
            String cleaned = stripFieldNum(normalize(word.getText()));
            if (cleaned.contains(target)) return word;

            if (similarity(cleaned, target) >= 0.78) {
                return word;
            }
        }

        // 3) 라인 n-gram contains / 유사도
        for (TextLine line : lines) {
            List<OcrWord> sorted = line.getWords().stream()
                    .sorted(Comparator.comparingDouble(OcrWord::getX))
                    .collect(Collectors.toList());

            for (int i = 0; i < sorted.size(); i++) {
                if (minY > 0 && sorted.get(i).getY() < minY) continue;
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(i + 4, sorted.size()); j++) {
                    sb.append(stripFieldNum(normalize(sorted.get(j).getText())));
                    String gram = sb.toString();
                    if (gram.contains(target) || similarity(gram, target) >= 0.84) {
                        return sorted.get(j);
                    }
                }
            }
        }

        return null;
    }

    private Set<String> candidateTerms(String anchor) {
        Set<String> out = new LinkedHashSet<>();
        out.add(normalize(anchor));
        for (String a : ALIAS.getOrDefault(anchor, Collections.emptyList())) {
            out.add(normalize(a));
        }
        return out;
    }

    private String normalize(String text) {
        return TextNormalizer.normalizeForMatch(text);
    }

    private String stripFieldNum(String text) {
        return text.replaceFirst("^[\\d①-⑳㉑-㉟\\s]+", "");
    }

    private double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.isEmpty() || b.isEmpty()) return 0;
        int dist = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        return 1.0 - (dist / (double) maxLen);
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }
}
