package exps.cariv.domain.registration.service;

import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.registration.entity.RegistrationDocument;
import exps.cariv.domain.registration.repository.RegistrationDocumentRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationQueryService {

    private final RegistrationDocumentRepository repo;

    @Transactional(readOnly = true)
    public RegistrationDocument getByVehicle(Long companyId, Long vehicleId) {
        return repo.findByCompanyIdAndRefTypeAndRefIdAndType(companyId, DocumentRefType.VEHICLE, vehicleId, DocumentType.REGISTRATION)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }
}
