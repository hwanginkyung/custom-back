package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.layout.NormalizedLayout;
import org.springframework.stereotype.Component;

/**
 * 자동차등록증 문서 파서 전략.
 */
@Component
public class VehicleRegistrationDocumentParser implements DocumentParser {

    @Override
    public DocumentType supports() {
        return DocumentType.VEHICLE_REGISTRATION;
    }

    @Override
    public VehicleRegistration parse(NormalizedLayout layout) {
        return new VehicleRegistrationParser(layout).parse();
    }
}

