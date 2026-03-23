package exps.cariv.domain.auction.service;

import exps.cariv.domain.auction.dto.response.AuctionDocumentResponse;
import exps.cariv.domain.auction.entity.AuctionDocument;
import exps.cariv.domain.auction.repository.AuctionDocumentRepository;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuctionQueryService {

    private final AuctionDocumentRepository auctionDocRepo;
    private final OcrParseJobRepository ocrJobRepo;
    private final OcrResultNormalizer ocrResultNormalizer;

    @Transactional(readOnly = true)
    public AuctionDocumentResponse getByVehicle(Long companyId, Long vehicleId) {
        AuctionDocument d = auctionDocRepo.findByCompanyIdAndRefTypeAndRefIdAndType(
                        companyId, DocumentRefType.VEHICLE, vehicleId, DocumentType.AUCTION_CERTIFICATE
                )
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        var ocrResult = ocrJobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, d.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(exps.cariv.domain.ocr.dto.response.OcrFieldResult::empty);

        return new AuctionDocumentResponse(
                d.getId(),
                d.getS3Key(),
                d.getOriginalFilename(),
                d.getUploadedAt(),
                d.getParsedAt(),
                d.toSnapshot(),
                ocrResult
        );
    }
}
