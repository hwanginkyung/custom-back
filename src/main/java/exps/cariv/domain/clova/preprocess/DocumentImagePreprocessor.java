package exps.cariv.domain.clova.preprocess;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * OCR 입력 전 이미지 전처리:
 * - 리사이즈(과대 이미지)
 * - 그레이스케일 + 대비 스트레칭
 * - 회전 보정(90/180/270)
 */
@Slf4j
@Component
public class DocumentImagePreprocessor {

    private static final int MAX_DIMENSION = 2300;
    private static final double LOW_PERCENTILE = 0.02;
    private static final double HIGH_PERCENTILE = 0.98;

    public byte[] preprocess(byte[] sourceBytes, String fileName) {
        try {
            BufferedImage src = decode(sourceBytes);
            if (src == null) {
                log.warn("Image decode failed for {}. Use original bytes.", fileName);
                return sourceBytes;
            }

            BufferedImage resized = resizeIfNeeded(src, MAX_DIMENSION);
            BufferedImage gray = toGrayscale(resized);
            BufferedImage contrasted = stretchContrast(gray);
            return encodeJpeg(contrasted, 0.92f);
        } catch (Exception e) {
            log.warn("Preprocess failed for {}: {}. Use original bytes.", fileName, e.getMessage());
            return sourceBytes;
        }
    }

    public byte[] rotate(byte[] sourceBytes, int degrees) throws IOException {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized == 0) return sourceBytes;

        BufferedImage src = decode(sourceBytes);
        if (src == null) return sourceBytes;

        BufferedImage rotated = switch (normalized) {
            case 90 -> rotate90(src);
            case 180 -> rotate180(src);
            case 270 -> rotate270(src);
            default -> src;
        };
        return encodeJpeg(rotated, 0.92f);
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        }
    }

    private BufferedImage resizeIfNeeded(BufferedImage src, int maxDim) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxDim) return ensureRgb(src);

        double scale = (double) maxDim / max;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private BufferedImage ensureRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private BufferedImage toGrayscale(BufferedImage src) {
        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return gray;
    }

    private BufferedImage stretchContrast(BufferedImage gray) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        WritableRaster src = gray.getRaster();
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                hist[src.getSample(x, y, 0)]++;
            }
        }

        int total = w * h;
        int low = percentile(hist, total, LOW_PERCENTILE);
        int high = percentile(hist, total, HIGH_PERCENTILE);
        if (high <= low + 8) {
            return gray;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster dst = out.getRaster();
        double scale = 255.0 / (high - low);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = src.getSample(x, y, 0);
                int nv = (int) Math.round((v - low) * scale);
                if (nv < 0) nv = 0;
                if (nv > 255) nv = 255;
                dst.setSample(x, y, 0, nv);
            }
        }
        return out;
    }

    private int percentile(int[] hist, int total, double p) {
        int target = (int) Math.round(total * p);
        int acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= target) return i;
        }
        return hist.length - 1;
    }

    private BufferedImage rotate90(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, h, w);
            AffineTransform tx = new AffineTransform();
            tx.translate(h, 0);
            tx.rotate(Math.PI / 2);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private BufferedImage rotate180(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            AffineTransform tx = new AffineTransform();
            tx.translate(w, h);
            tx.rotate(Math.PI);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private BufferedImage rotate270(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, h, w);
            AffineTransform tx = new AffineTransform();
            tx.translate(0, w);
            tx.rotate(-Math.PI / 2);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
