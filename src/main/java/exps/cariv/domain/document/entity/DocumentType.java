package exps.cariv.domain.document.entity;

public enum DocumentType {

    EXPORT_CERTIFICATE,      // 수출필증(=수출신고필증)
    DEREGISTRATION,          // 말소증
    REGISTRATION,            // 자동차 등록증
    AUCTION_CERTIFICATE,     // 경락사실확인서
    ID_CARD,                 // 신분증
    BIZ_REGISTRATION,        // 사업자등록
    CONTRACT,                // 매매계약서
    SIGN,                    // 사인방 (화주 서명)
    SEAL,                    // 인감 (화주 인감)

    UNKNOWN;
}
