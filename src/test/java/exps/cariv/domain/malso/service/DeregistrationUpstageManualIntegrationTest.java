package exps.cariv.domain.malso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.malso.dto.DeregParseResult;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import exps.cariv.domain.upstage.service.UpstageService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수동 실행 전용:
 * - 실제 Upstage OCR 응답을 받아서
 * - 현재 말소 파서 결과를 다건 회귀 검증한다.
 *
 * 기본 test 실행에는 포함되지만, 아래 시스템 프로퍼티가 없으면 자동 skip 된다.
 *   -DrunUpstageMalsoIT=true
 *
 * 샘플 경로/출력 경로 변경:
 *   -DupstageMalsoSamplesDir=tmp/upstage-samples/malso
 *   -DupstageMalsoOutDir=tmp/upstage-samples/out
 */
@SpringBootTest
class DeregistrationUpstageManualIntegrationTest {

    private static final String DEFAULT_PLACEHOLDER_KEY = "up_HLoBa580X6eNgppa0SeG7xLf8uuIP";

    @Autowired
    private UpstageService upstageService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeregistrationParserService parserService;

    @Autowired
    private Environment environment;

    @Test
    void runUpstageRegressionOnSampleFiles() throws Exception {
        boolean enabled = Boolean.parseBoolean(System.getProperty("runUpstageMalsoIT", "false"));
        Assumptions.assumeTrue(enabled, "manual integration test is disabled");

        String apiKey = environment.getProperty("upstage.api-key");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "upstage.api-key is empty");
        Assumptions.assumeTrue(!DEFAULT_PLACEHOLDER_KEY.equals(apiKey), "upstage.api-key is placeholder");

        Path sampleDir = Path.of(System.getProperty("upstageMalsoSamplesDir", "tmp/upstage-samples/malso"));
        Assumptions.assumeTrue(Files.isDirectory(sampleDir), "sample dir not found: " + sampleDir.toAbsolutePath());

        List<Path> files;
        try (var stream = Files.list(sampleDir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedDocument)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        Assumptions.assumeTrue(!files.isEmpty(), "no sample files in " + sampleDir.toAbsolutePath());

        Path outDir = Path.of(System.getProperty("upstageMalsoOutDir", "tmp/upstage-samples/out"));
        Files.createDirectories(outDir);

        List<String> failures = new ArrayList<>();

        for (Path file : files) {
            String filename = file.getFileName().toString();
            String rawJson = upstageService.parseDocuments(0L, new FileSystemResource(file), filename);
            UpstageResponse response = objectMapper.readValue(rawJson, UpstageResponse.class);
            DeregParseResult parsed = parserService.parseAndValidate(response);

            Path rawOut = outDir.resolve(filename + ".upstage.json");
            Path parsedOut = outDir.resolve(filename + ".parsed.json");
            Files.writeString(rawOut, rawJson, StandardCharsets.UTF_8);
            Files.writeString(parsedOut, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed), StandardCharsets.UTF_8);

            System.out.printf(
                    "[MALSO][%s] registrationNo=%s vin=%s deRegistrationDate=%s reason=%s missing=%s errors=%s%n",
                    filename,
                    parsed.parsed().registrationNo(),
                    parsed.parsed().vin(),
                    parsed.parsed().deRegistrationDate(),
                    parsed.parsed().deRegistrationReason(),
                    parsed.missingFields(),
                    parsed.errorFields()
            );

            if (!parsed.missingFields().isEmpty() || !parsed.errorFields().isEmpty()) {
                failures.add(filename + " missing=" + parsed.missingFields() + " errors=" + parsed.errorFields());
            }
        }

        assertTrue(
                failures.isEmpty(),
                "Some samples have parse issues:\n" + String.join("\n", failures)
        );
    }

    private boolean isSupportedDocument(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".webp")
                || name.endsWith(".heic")
                || name.endsWith(".heif");
    }
}
