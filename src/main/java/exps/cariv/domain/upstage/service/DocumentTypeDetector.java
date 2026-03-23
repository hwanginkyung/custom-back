package exps.cariv.domain.upstage.service;


import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import org.springframework.stereotype.Service;


import java.util.List;
import java.util.stream.Collectors;

@Service
public class DocumentTypeDetector {

    // 말소증명서 헤더/타이틀 쪽에 자주 나오는 문구들
    private static final List<String> DEREGISTRATION_KEYWORDS = List.of(
            "자동차 말소 사실 증명서",
            "자동차말소사실증명서",
            "De-registration Certificate",
            "De-Registration Certificate",
            "말소등록 일",
            "Reason for De-registration"
    );

    // (등록원부/인보이스도 나중에 추가 가능)
    private static final List<String> REGISTRATION_KEYWORDS = List.of(
            "자동차등록증",
            "자동차 등록증",
            "Vehicle Registration",
            "Registration Certificate"
    );

    private static final List<String> INVOICE_KEYWORDS = List.of(
            "INVOICE",
            "Commercial Invoice",
            "세금계산서",
            "INVOICE NO"
    );
    private static final List<String> CONTRACT_KEYWORDS = List.of(
            "매매계약서",
            "매매 계약서",
            "자동차매매계약서"
    );
    private static final List<String> AUCTION_KEYWORDS = List.of(
            "경락사실확인서",
            "경락 사실 확인서",
            "낙찰사실확인서"
    );
    private static final List<String> EXPORT_DECLARATION_KEYWORDS = List.of(
            "수출신고필증",
            "수출 신고 필증"
             // 품명 USED CAR
    );
    private static final List<String> BIZ_REG_KEYWORDS = List.of(
            "사업자등록증",
            "사업자 등록증",
            "사업자등록 번호",
            "Business Registration"
    );
    private static final List<String> ID_CARD_KEYWORDS = List.of(
            "주민등록증",
            "주민 등록증",
            "운전면허증",
            "운전 면허증",
            "신분증"
    );

    public static DocumentType detect(UpstageResponse res) {
        // 1) 모든 element의 텍스트 내용 합치기
        String allText = res.elements().stream()
                .map(e -> {
                    if (e.content() == null) return "";
                    // 우선순위: text -> markdown -> html
                    if (notBlank(e.content().text())) return e.content().text();
                    if (notBlank(e.content().markdown())) return e.content().markdown();
                    if (notBlank(e.content().html())) return stripHtml(e.content().html());
                    return "";
                })
                .collect(Collectors.joining(" "));

        // 2) 공백 제거 + 소문자 통일
        String normalized = normalize(allText);

        // 3) 키워드 매칭
        if (containsAny(normalized, DEREGISTRATION_KEYWORDS)) {
            return DocumentType.DEREGISTRATION;
        }

        if (containsAny(normalized, REGISTRATION_KEYWORDS)) {
            return DocumentType.REGISTRATION;
        }

/*        if (containsAny(normalized, INVOICE_KEYWORDS)) {
            return DocumentType.INVOICE;
        }*/
        if (containsAny(normalized, CONTRACT_KEYWORDS)) {
            return DocumentType.CONTRACT;
        }
        if (containsAny(normalized, AUCTION_KEYWORDS)) {
            return DocumentType.AUCTION_CERTIFICATE;
        }
        if (containsAny(normalized, EXPORT_DECLARATION_KEYWORDS)) {
            return DocumentType.EXPORT_CERTIFICATE;
        }
        if (containsAny(normalized, BIZ_REG_KEYWORDS)) {
            return DocumentType.BIZ_REGISTRATION;
        }
        if (containsAny(normalized, ID_CARD_KEYWORDS)) {
            return DocumentType.ID_CARD;
        }

        return DocumentType.UNKNOWN;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // Jsoup 안 쓰고 대충 태그 제거용 (정교할 필요까진 없음)
    private static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ");
    }

    // 공백 다 없애고 소문자로
    private static String normalize(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\s+", "")  // 모든 공백 제거 (띄어쓰기/줄바꿈 무시)
                .toLowerCase();
    }

    // 라벨도 똑같이 정규화해서 포함 여부 체크
    private static boolean containsAny(String normalizedText, List<String> keywords) {
        for (String keyword : keywords) {
            String normKeyword = normalize(keyword);
            if (normalizedText.contains(normKeyword)) {
                return true;
            }
        }
        return false;
    }
}
