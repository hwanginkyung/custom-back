package exps.cariv.domain.contract.service;

import exps.cariv.domain.contract.dto.ContractSnapshot;
import exps.cariv.domain.contract.dto.request.ContractSnapshotUpdateRequest;
import exps.cariv.domain.contract.entity.ContractDocument;
import exps.cariv.domain.contract.repository.ContractDocumentRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매매계약서 쓰기 서비스 (Command).
 */
@Service
@RequiredArgsConstructor
public class ContractCommandService {

    private final ContractDocumentRepository contractDocRepo;

    /**
     * 매매계약서 OCR 스냅샷 수동 수정 저장.
     * vehicleId 연결 전에도 documentId만으로 저장 가능하다.
     */
    @Transactional
    public void updateSnapshot(Long companyId,
                               Long documentId,
                               ContractSnapshotUpdateRequest req) {
        ContractDocument doc = contractDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        ContractSnapshot snapshot = new ContractSnapshot(
                req.registrationNo(),
                req.vehicleType(),
                req.model(),
                req.chassisNo()
        );
        doc.applyManualSnapshot(snapshot);
        contractDocRepo.save(doc);
    }
}
