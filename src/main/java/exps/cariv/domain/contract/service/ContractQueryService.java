package exps.cariv.domain.contract.service;

import exps.cariv.domain.contract.dto.response.ContractDocumentResponse;
import exps.cariv.domain.contract.entity.ContractDocument;
import exps.cariv.domain.contract.repository.ContractDocumentRepository;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매매계약서 조회 서비스 (Query).
 */
@Service
@RequiredArgsConstructor
public class ContractQueryService {

    private final ContractDocumentRepository contractDocRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrResultNormalizer ocrResultNormalizer;

    /**
     * documentId 기준 매매계약서 OCR 스냅샷 + 인식 결과 조회.
     * 차량 생성 전(pre-create)에도 조회 가능하다.
     */
    @Transactional(readOnly = true)
    public ContractDocumentResponse getSnapshot(Long companyId, Long documentId) {
        ContractDocument doc = contractDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        var ocrResult = jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, doc.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(OcrFieldResult::empty);

        return new ContractDocumentResponse(
                doc.getId(),
                doc.getS3Key(),
                doc.getOriginalFilename(),
                doc.getUploadedAt(),
                doc.getParsedAt(),
                doc.toSnapshot(),
                ocrResult
        );
    }

    /**
     * jobId 기준 매매계약서 OCR 스냅샷 + 인식 결과 조회.
     */
    @Transactional(readOnly = true)
    public ContractDocumentResponse getSnapshotByJobId(Long companyId, Long jobId) {
        OcrParseJob job = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (job.getDocumentType() != DocumentType.CONTRACT) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "매매계약서 OCR 작업(jobId)이 아닙니다.");
        }
        return getSnapshot(companyId, job.getVehicleDocumentId());
    }
}
