package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.layout.NormalizedLayout;
import exps.cariv.domain.clova.layout.NormalizedToken;
import exps.cariv.domain.clova.layout.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 문서 타입 분류기.
 * 처음 단계에서는 anchor 키워드 기반의 lightweight 점수 방식을 사용.
 */
@Component
public class DocumentTypeClassifier {

    private static final List<String> REGISTRATION_ANCHORS = List.of(
            "자동차등록증", "자동차등록번호", "차대번호", "최초등록일", "제원"
    );
    private static final List<String> DEREGISTRATION_STRONG = List.of(
            "말소사실증명", "자동차말소등록사실증명", "말소증명서"
    );
    private static final List<String> DEREGISTRATION_WEAK = List.of(
            "말소", "등록원부", "저당권말소"
    );
    private static final List<String> ID_CARD_ANCHORS = List.of(
            "주민등록증", "주민등록번호", "성명", "주소"
    );

    public DocumentTypeResult classify(NormalizedLayout layout) {
        if (layout == null || layout.getTokens() == null || layout.getTokens().isEmpty()) {
            return new DocumentTypeResult(DocumentType.UNKNOWN, 0.0, "token 없음");
        }

        String corpus = layout.getTokens().stream()
                .map(NormalizedToken::getNormalizedText)
                .reduce("", String::concat);

        double registrationScore = scoreFor(REGISTRATION_ANCHORS, corpus);
        double deregStrong = scoreFor(DEREGISTRATION_STRONG, corpus);
        double deregWeak = scoreFor(DEREGISTRATION_WEAK, corpus);
        double deregScore = deregStrong > 0 ? (deregStrong * 0.75 + deregWeak * 0.25) : 0.0;
        double idScore = scoreFor(ID_CARD_ANCHORS, corpus);

        Score best = new Score(DocumentType.VEHICLE_REGISTRATION, registrationScore, "registration anchor score");
        if (deregScore > best.score) best = new Score(DocumentType.VEHICLE_DEREGISTRATION, deregScore, "deregistration anchor score");
        if (idScore > best.score) best = new Score(DocumentType.ID_CARD, idScore, "id card anchor score");

        if (best.score < 0.25) {
            return new DocumentTypeResult(DocumentType.UNKNOWN, best.score, "점수 부족");
        }
        return new DocumentTypeResult(best.type, best.score, best.reason);
    }

    private double scoreFor(List<String> anchors, String corpus) {
        if (anchors.isEmpty()) return 0.0;

        int matched = 0;
        for (String anchor : anchors) {
            String target = TextNormalizer.normalizeForMatch(anchor);
            boolean found = corpus.contains(target);
            if (found) matched++;
        }
        return matched / (double) anchors.size();
    }

    private static final class Score {
        private final DocumentType type;
        private final double score;
        private final String reason;

        private Score(DocumentType type, double score, String reason) {
            this.type = type;
            this.score = score;
            this.reason = reason;
        }
    }
}
