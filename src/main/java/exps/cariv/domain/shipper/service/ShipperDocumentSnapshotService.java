package exps.cariv.domain.shipper.service;

import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.ocr.dto.response.OcrFieldResult;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.ocr.service.OcrResultNormalizer;
import exps.cariv.domain.shipper.dto.BizRegSnapshot;
import exps.cariv.domain.shipper.dto.IdCardSnapshot;
import exps.cariv.domain.shipper.dto.request.BizRegSnapshotUpdateRequest;
import exps.cariv.domain.shipper.dto.request.IdCardSnapshotUpdateRequest;
import exps.cariv.domain.shipper.dto.response.BizRegDocumentResponse;
import exps.cariv.domain.shipper.dto.response.IdCardDocumentResponse;
import exps.cariv.domain.shipper.entity.BizRegDocument;
import exps.cariv.domain.shipper.entity.IdCardDocument;
import exps.cariv.domain.shipper.entity.Shipper;
import exps.cariv.domain.shipper.repository.BizRegDocumentRepository;
import exps.cariv.domain.shipper.repository.IdCardDocumentRepository;
import exps.cariv.domain.shipper.repository.ShipperRepository;
import exps.cariv.global.aws.S3ObjectReader;
import exps.cariv.global.aws.S3Upload;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 화주 OCR 문서(BIZ_REG/ID_CARD) 스냅샷 조회/수정 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipperDocumentSnapshotService {

    private final BizRegDocumentRepository bizRegDocRepo;
    private final IdCardDocumentRepository idCardDocRepo;
    private final ShipperRepository shipperRepo;
    private final OcrParseJobRepository jobRepo;
    private final OcrResultNormalizer ocrResultNormalizer;
    private final S3ObjectReader s3Reader;
    private final S3Upload s3Upload;

    @Transactional(readOnly = true)
    public BizRegDocumentResponse getBizRegSnapshot(Long companyId, Long documentId) {
        BizRegDocument doc = bizRegDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        OcrFieldResult ocrResult = jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, doc.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(OcrFieldResult::empty);

        return new BizRegDocumentResponse(
                doc.getId(),
                doc.getS3Key(),
                doc.getOriginalFilename(),
                doc.getUploadedAt(),
                doc.getParsedAt(),
                doc.toSnapshot(),
                ocrResult
        );
    }

    @Transactional(readOnly = true)
    public BizRegDocumentResponse getBizRegSnapshotByJobId(Long companyId, Long jobId) {
        OcrParseJob job = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (job.getDocumentType() != DocumentType.BIZ_REGISTRATION) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "사업자등록증 OCR 작업(jobId)이 아닙니다.");
        }
        return getBizRegSnapshot(companyId, job.getVehicleDocumentId());
    }

    @Transactional
    public void updateBizRegSnapshot(Long companyId, Long documentId, BizRegSnapshotUpdateRequest req) {
        BizRegDocument doc = bizRegDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        BizRegSnapshot snapshot = new BizRegSnapshot(
                req.companyName(),
                req.representativeName(),
                req.bizNumber(),
                req.bizType(),
                req.bizCategory(),
                req.bizAddress(),
                req.openDate()
        );
        doc.applyManualSnapshot(snapshot);
        bizRegDocRepo.save(doc);
        syncShipperMasterFromBizReg(companyId, doc.getRefId(), req.bizNumber(), req.bizAddress());
    }

    @Transactional(readOnly = true)
    public IdCardDocumentResponse getIdCardSnapshot(Long companyId, Long documentId) {
        IdCardDocument doc = idCardDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        OcrFieldResult ocrResult = jobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                        companyId, doc.getId(), OcrJobStatus.SUCCEEDED
                )
                .map(job -> ocrResultNormalizer.normalize(job.getDocumentType(), job.getResultJson()))
                .orElseGet(OcrFieldResult::empty);

        return new IdCardDocumentResponse(
                doc.getId(),
                doc.getS3Key(),
                doc.getOriginalFilename(),
                doc.getUploadedAt(),
                doc.getParsedAt(),
                doc.toSnapshot(),
                ocrResult
        );
    }

    @Transactional(readOnly = true)
    public IdCardDocumentResponse getIdCardSnapshotByJobId(Long companyId, Long jobId) {
        OcrParseJob job = jobRepo.findByCompanyIdAndId(companyId, jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        if (job.getDocumentType() != DocumentType.ID_CARD) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "신분증 OCR 작업(jobId)이 아닙니다.");
        }
        return getIdCardSnapshot(companyId, job.getVehicleDocumentId());
    }

    @Transactional
    public void updateIdCardSnapshot(Long companyId, Long documentId, IdCardSnapshotUpdateRequest req) {
        IdCardDocument doc = idCardDocRepo.findByCompanyIdAndId(companyId, documentId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        IdCardSnapshot snapshot = new IdCardSnapshot(
                req.holderName(),
                req.idNumber(),
                req.idAddress(),
                req.issueDate()
        );
        doc.applyManualSnapshot(snapshot);
        idCardDocRepo.save(doc);

        // 회전 요청이 있으면 S3 이미지를 회전 후 덮어쓰기
        if (req.rotateDegrees() != null && req.rotateDegrees() % 360 != 0) {
            rotateAndOverwriteS3Image(doc.getS3Key(), req.rotateDegrees());
        }
    }

    private void rotateAndOverwriteS3Image(String s3Key, int degrees) {
        try {
            byte[] original = s3Reader.readBytes(s3Key);
            if (original == null) {
                log.warn("[ImageRotate] S3 object not found: {}", s3Key);
                return;
            }

            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) {
                log.warn("[ImageRotate] Cannot read image: {}", s3Key);
                return;
            }

            int normalizedDeg = ((degrees % 360) + 360) % 360;
            boolean swap = (normalizedDeg == 90 || normalizedDeg == 270);
            int newW = swap ? src.getHeight() : src.getWidth();
            int newH = swap ? src.getWidth() : src.getHeight();

            BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = rotated.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            AffineTransform at = new AffineTransform();
            at.translate(newW / 2.0, newH / 2.0);
            at.rotate(Math.toRadians(normalizedDeg));
            at.translate(-src.getWidth() / 2.0, -src.getHeight() / 2.0);
            g2d.drawImage(src, at, null);
            g2d.dispose();

            String format = s3Key.toLowerCase().endsWith(".png") ? "png" : "jpg";
            String contentType = format.equals("png") ? "image/png" : "image/jpeg";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(rotated, format, baos);

            s3Upload.uploadBytes(s3Key, baos.toByteArray(), contentType);
            log.info("[ImageRotate] Rotated {}° and saved: {}", normalizedDeg, s3Key);
        } catch (Exception e) {
            log.error("[ImageRotate] Failed to rotate image: {}", s3Key, e);
        }
    }

    private void syncShipperMasterFromBizReg(Long companyId, Long shipperId,
                                             String bizNumber, String bizAddress) {
        if (shipperId == null) {
            return;
        }

        String normalizedBizNumber = normalizeBizNumber(bizNumber);
        String normalizedAddress = normalizeNonBlank(bizAddress);
        if (normalizedBizNumber == null && normalizedAddress == null) {
            return;
        }

        Shipper shipper = shipperRepo.findByIdAndCompanyId(shipperId, companyId).orElse(null);
        if (shipper == null) {
            return;
        }

        shipper.update(
                shipper.getName(),
                shipper.getType(),
                shipper.getShipperType(),
                shipper.getPhone(),
                normalizedBizNumber,
                normalizedAddress
        );
    }

    private String normalizeBizNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != 10) {
            return null;
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }

    private String normalizeNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
