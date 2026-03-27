package exps.cariv.domain.shipper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.notification.entity.NotificationType;
import exps.cariv.domain.notification.service.NotificationCommandService;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.shipper.dto.ParsedIdCard;
import exps.cariv.domain.shipper.entity.IdCardDocument;
import exps.cariv.domain.shipper.repository.IdCardDocumentRepository;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import exps.cariv.domain.upstage.service.DocumentTypeDetector;
import exps.cariv.domain.upstage.service.UpstageService;
import exps.cariv.domain.upstage.service.UpstageTablePayloadBuilder;
import exps.cariv.global.aws.S3ObjectReader;
import exps.cariv.global.aws.S3Upload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Objects;

/**
 * 신분증 OCR 처리.
 * - 차량 소유자 신분증: OCR 완료 알림 전송
 * - (레거시) 화주 신분증 문서: 내부 결과 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdCardOcrService {

    private final UpstageService upstageService;
    private final ObjectMapper mapper;
    private final S3ObjectReader s3Reader;
    private final S3Upload s3Upload;
    private final IdCardParserService parser;
    private final IdCardLlmRefiner llmRefiner;
    private final DocumentRepository documentRepo;
    private final IdCardDocumentRepository idCardRepo;
    private final NotificationCommandService notificationCommandService;

    @Transactional
    public void processJob(Long companyId, OcrParseJob job) {
        Document doc = documentRepo.findById(job.getVehicleDocumentId())
                .orElseThrow(() -> new IllegalStateException("ID_CARD document not found id=" + job.getVehicleDocumentId()));
        if (!Objects.equals(doc.getCompanyId(), companyId)) {
            throw new IllegalStateException("ID_CARD document company mismatch id=" + job.getVehicleDocumentId());
        }
        if (doc.getType() != DocumentType.ID_CARD) {
            throw new IllegalStateException("document type mismatch id=" + job.getVehicleDocumentId()
                    + ", type=" + doc.getType());
        }

        byte[] bytes = s3Reader.readBytes(job.getS3KeySnapshot());
        if (bytes == null) {
            throw new IllegalStateException("S3 object not found: key=" + job.getS3KeySnapshot());
        }

        // 이미지 회전 보정: 세로 이미지(높이 > 너비)이면 90도 회전하여 가로로 변환
        bytes = correctImageRotation(bytes, doc.getS3Key(), doc.getOriginalFilename());

        Resource resource = new ByteArrayResource(bytes);

        String upstageJson;
        UpstageResponse res;
        try {
            upstageJson = upstageService.parseDocuments(companyId, resource, doc.getOriginalFilename());
            res = mapper.readValue(upstageJson, UpstageResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Upstage OCR 호출 실패: " + e.getMessage(), e);
        }

        // 문서 타입 검증 (경고만)
        DocumentType detected = DocumentTypeDetector.detect(res);
        if (detected != DocumentType.ID_CARD) {
            log.warn("[IdCard OCR] 문서 타입 불일치: detected={}, jobId={}", detected, job.getId());
        }

        ParsedIdCard parsed = parser.parse(res);
        // LLM 보정: 누락 필드가 있으면 LLM으로 채우기 시도
        parsed = llmRefiner.refineIfNeeded(parsed, res);
        String tableHtmlJson;
        try {
            tableHtmlJson = UpstageTablePayloadBuilder.buildTableHtmlJson(mapper, res);
        } catch (Exception e) {
            throw new IllegalStateException("테이블 파싱 실패: " + e.getMessage(), e);
        }
        String resultJson;
        try {
            resultJson = buildResultJson(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Job 결과 직렬화 실패: " + e.getMessage(), e);
        }

        if (doc instanceof IdCardDocument idCardDocument) {
            idCardDocument.applyOcrResult(
                    parsed.holderName(),
                    parsed.idNumber(),
                    parsed.idAddress(),
                    parsed.issueDate(),
                    tableHtmlJson
            );
            idCardRepo.save(idCardDocument);
        } else {
            // 차량 소유자 신분증은 공통 Document에 OCR 성공 상태만 반영한다.
            doc.markOcrDraft();
            documentRepo.save(doc);
        }

        try {
            job.markSucceeded(resultJson);
        } catch (Exception e) {
            throw new IllegalStateException("Job 상태 저장 실패: " + e.getMessage(), e);
        }
        log.info("[IdCard OCR] 성공 jobId={}, refType={}, refId={}", job.getId(), doc.getRefType(), doc.getRefId());

        if (doc.getRefType() == DocumentRefType.VEHICLE) {
            Long linkedVehicleId = (doc.getRefId() != null && doc.getRefId() > 0) ? doc.getRefId() : null;
            String msg = "소유자 신분증 OCR 완료";
            notificationCommandService.createOcr(
                    companyId,
                    job.getRequestedByUserId(),
                    NotificationType.OCR_COMPLETE,
                    DocumentType.ID_CARD,
                    linkedVehicleId,
                    job.getId(),
                    "소유자 신분증 OCR 완료",
                    msg
            );
        }
    }

    private String buildResultJson(ParsedIdCard parsed) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode parsedNode = mapper.createObjectNode();
        putNullable(parsedNode, "holderName", parsed.holderName());
        putNullable(parsedNode, "idNumber", parsed.idNumber());
        putNullable(parsedNode, "idAddress", parsed.idAddress());
        putNullable(parsedNode, "issueDate", parsed.issueDate());
        root.set("parsed", parsedNode);

        root.set("errorFields", mapper.createArrayNode());
        ArrayNode missing = mapper.createArrayNode();
        if (isBlank(parsed.holderName())) missing.add("holderName");
        if (isBlank(parsed.idNumber())) missing.add("idNumber");
        if (isBlank(parsed.idAddress())) missing.add("idAddress");
        root.set("missingFields", missing);

        return mapper.writeValueAsString(root);
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(key);
            return;
        }
        node.put(key, value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 신분증 이미지가 회전되어 있으면 바르게 회전하여 S3에 덮어쓴다.
     *
     * <p>보정 순서:
     * 1단계 — EXIF Orientation 태그 기반 회전 (핸드폰 사진)
     * 2단계 — 신분증은 가로 형태이므로, EXIF 보정 후에도 세로(높이>너비)이면 90° 회전
     */
    private byte[] correctImageRotation(byte[] originalBytes, String s3Key, String filename) {
        if (filename == null) return originalBytes;
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png")) {
            return originalBytes; // 이미지 파일이 아니면 스킵
        }

        try {
            // ── 1단계: EXIF orientation 읽기 ──
            int exifOrientation = readExifOrientation(originalBytes);

            BufferedImage img = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (img == null) return originalBytes;

            boolean changed = false;

            // EXIF orientation에 따른 변환 (1=정상, 그 외 회전/반전)
            if (exifOrientation > 1) {
                img = applyExifOrientation(img, exifOrientation);
                changed = true;
                log.info("[IdCard] EXIF orientation={} 보정 적용: {}x{}", exifOrientation, img.getWidth(), img.getHeight());
            }

            // ── 2단계: 신분증은 가로 형태 — 세로이면 90° 반시계 회전 ──
            if (img.getHeight() > img.getWidth()) {
                log.info("[IdCard] 이미지 가로 보정: {}x{} → 90° 반시계 회전", img.getWidth(), img.getHeight());
                img = rotateCCW90(img);
                changed = true;
            }

            if (!changed) return originalBytes;

            // ── 인코딩 & S3 덮어쓰기 ──
            String format = lower.endsWith(".png") ? "png" : "jpg";
            byte[] resultBytes = encodeImage(img, format);

            if (s3Key != null && !s3Key.isBlank()) {
                try {
                    String contentType = "png".equals(format) ? "image/png" : "image/jpeg";
                    s3Upload.uploadBytes(s3Key, resultBytes, contentType);
                    log.info("[IdCard] 회전 보정 이미지 S3 업로드 완료: {}", s3Key);
                } catch (Exception e) {
                    log.warn("[IdCard] 회전 보정 이미지 S3 업로드 실패: {}", e.getMessage());
                }
            }

            return resultBytes;
        } catch (Exception e) {
            log.warn("[IdCard] 이미지 회전 보정 실패, 원본 사용: {}", e.getMessage());
            return originalBytes;
        }
    }

    /** EXIF Orientation 태그 읽기 (1~8). 실패 시 1(정상) 반환. */
    private int readExifOrientation(byte[] imageBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("[IdCard] EXIF 읽기 실패 (무시): {}", e.getMessage());
        }
        return 1; // 정상
    }

    /**
     * EXIF Orientation 값에 따라 이미지를 변환.
     * <pre>
     * 1: 정상              5: 반전 + 90°CW
     * 2: 좌우반전           6: 90°CW  (핸드폰 세로 촬영 시 가장 흔함)
     * 3: 180°              7: 반전 + 270°CW
     * 4: 상하반전           8: 270°CW (=90°CCW)
     * </pre>
     */
    private BufferedImage applyExifOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();

        AffineTransform t = new AffineTransform();
        boolean swap = false; // 가로세로 교체 필요 여부

        switch (orientation) {
            case 2: // 좌우반전
                t.scale(-1, 1);
                t.translate(-w, 0);
                break;
            case 3: // 180°
                t.translate(w, h);
                t.rotate(Math.PI);
                break;
            case 4: // 상하반전
                t.scale(1, -1);
                t.translate(0, -h);
                break;
            case 5: // 반전 + 90°CW
                t.rotate(Math.PI / 2);
                t.scale(1, -1);
                swap = true;
                break;
            case 6: // 90°CW
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
                swap = true;
                break;
            case 7: // 반전 + 270°CW
                t.scale(-1, 1);
                t.translate(-h, 0);
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
                swap = true;
                break;
            case 8: // 270°CW (=90°CCW)
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
                swap = true;
                break;
            default:
                return img;
        }

        int newW = swap ? h : w;
        int newH = swap ? w : h;
        int type = img.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : img.getType();

        BufferedImage result = new BufferedImage(newW, newH, type);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, t, null);
        g.dispose();
        return result;
    }

    /** 90° 반시계(CCW) 회전. */
    private BufferedImage rotateCCW90(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int type = src.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : src.getType();
        BufferedImage dst = new BufferedImage(h, w, type);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform at = new AffineTransform();
        at.translate(0, w);
        at.rotate(-Math.PI / 2);
        g.drawImage(src, at, null);
        g.dispose();
        return dst;
    }

    /** BufferedImage → byte[] 인코딩. JPEG는 0.92 품질. */
    private byte[] encodeImage(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if ("jpg".equals(format) || "jpeg".equals(format)) {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.92f);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                    writer.setOutput(ios);
                    // JPEG는 알파 채널 지원 안함 — RGB로 변환
                    BufferedImage rgb = img;
                    if (img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_4BYTE_ABGR) {
                        rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = rgb.createGraphics();
                        g.setColor(Color.WHITE);
                        g.fillRect(0, 0, img.getWidth(), img.getHeight());
                        g.drawImage(img, 0, 0, null);
                        g.dispose();
                    }
                    writer.write(null, new IIOImage(rgb, null, null), param);
                }
                writer.dispose();
            } else {
                ImageIO.write(img, "jpg", bos);
            }
        } else {
            ImageIO.write(img, format, bos);
        }
        return bos.toByteArray();
    }
}
