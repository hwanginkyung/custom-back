package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.layout.NormalizedLayout;

/**
 * 문서 타입별 parser 전략 인터페이스.
 */
public interface DocumentParser {
    DocumentType supports();
    VehicleRegistration parse(NormalizedLayout layout);
}
