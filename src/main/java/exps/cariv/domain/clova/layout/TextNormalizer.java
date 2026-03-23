package exps.cariv.domain.clova.layout;

import java.util.Locale;

/**
 * 공통 텍스트 정규화 유틸.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * anchor/키워드 매칭용 정규화
     * - 공백/특수문자 제거
     * - 영문 대문자화
     */
    public static String normalizeForMatch(String text) {
        if (text == null) return "";
        return text.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9A-Z가-힣]", "");
    }
}
