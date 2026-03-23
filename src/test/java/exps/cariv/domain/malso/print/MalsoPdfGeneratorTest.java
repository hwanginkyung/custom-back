package exps.cariv.domain.malso.print;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MalsoPdfGeneratorTest {

    private final MalsoPdfGenerator generator = new MalsoPdfGenerator();

    @Test
    void generate_producesValidPdfWithFullA4Layout() throws IOException {
        // 더미 사인방 이미지 생성 (빨간 도장 느낌)
        byte[] signImage = createDummySignImage();

        MalsoXlsxData data = new MalsoXlsxData(
                "홍길동",
                "900101-1234567",
                "",
                "175로3480",
                "KNAG6412BNA193171",
                null,
                "해피카",
                "900101",
                "해피카",
                "홍길동",
                "123-45-67890",
                "서울특별시 강남구 테헤란로 100",
                signImage
        );

        byte[] pdf = generator.generate(data);

        assertThat(pdf).isNotNull();
        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        Path outPath = Path.of(System.getProperty("user.home"), "Downloads", "test_malso_generated.pdf");
        Files.write(outPath, pdf);
        System.out.println("PDF saved to: " + outPath);
    }

    /** 테스트용 더미 도장 이미지 생성 (빨간 원 + 텍스트) */
    private byte[] createDummySignImage() throws IOException {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 빨간 원 (도장)
        g.setColor(new Color(200, 30, 30));
        g.setStroke(new BasicStroke(6));
        g.drawOval(20, 20, 160, 160);

        // 텍스트
        g.setFont(new Font("SansSerif", Font.BOLD, 36));
        g.drawString("SIGN", 50, 110);

        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
