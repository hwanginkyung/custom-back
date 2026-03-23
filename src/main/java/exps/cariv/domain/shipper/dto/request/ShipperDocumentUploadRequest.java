package exps.cariv.domain.shipper.dto.request;

/**
 * 화주 문서 업로드 요청.
 * type: SIGN, CEO_ID(=ID_CARD), BIZ_REG(=BIZ_REGISTRATION)
 */
public record ShipperDocumentUploadRequest(
        String type,
        Long shipperId
) {}
