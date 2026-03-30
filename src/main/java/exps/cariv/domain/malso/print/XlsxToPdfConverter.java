package exps.cariv.domain.malso.print;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LibreOffice를 이용하여 XLSX → PDF 변환.
 * 서버에 LibreOffice가 설치되어 있어야 합니다.
 * (apt-get install libreoffice-calc)
 */
@Component
@Slf4j
public class XlsxToPdfConverter {

    /**
     * XLSX 바이트를 받아 PDF 바이트로 변환합니다.
     * LibreOffice headless 모드를 사용합니다.
     */
    public byte[] convert(byte[] xlsxBytes) {
        return convertWithExtension(xlsxBytes, "xlsx");
    }

    /**
     * XLS (구 형식) 바이트를 받아 PDF 바이트로 변환합니다.
     */
    public byte[] convertXls(byte[] xlsBytes) {
        return convertWithExtension(xlsBytes, "xls");
    }

    private byte[] convertWithExtension(byte[] inputBytes, String extension) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("xlsx-to-pdf-");
            Path xlsxFile = tempDir.resolve("input." + extension);
            Files.write(xlsxFile, inputBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    "libreoffice",
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", tempDir.toString(),
                    xlsxFile.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("[XlsxToPdfConverter] LibreOffice failed exitCode={} output={}", exitCode, output);
                throw new IllegalStateException("LibreOffice conversion failed: " + output);
            }

            Path pdfFile = tempDir.resolve("input.pdf");
            if (!Files.exists(pdfFile)) {
                throw new IllegalStateException("PDF output file not found after conversion");
            }

            return Files.readAllBytes(pdfFile);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("XLSX to PDF conversion failed", e);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null) return;
        try {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                    });
        } catch (IOException ignore) {}
    }
}
