package exps.cariv.domain.registration.service;

import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.clova.service.VehicleOcrService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 수동 실행 전용:
 * - 실제 CLOVA OCR 호출로 VehicleOcrService 흐름을 검증한다.
 *
 * 실행:
 *   ./gradlew test --tests exps.cariv.domain.registration.service.RegistrationVehicleOcrManualIntegrationTest \
 *     -DrunRegistrationVehicleOcrIT=true \
 *     -DregistrationVehicleOcrImage=/absolute/path/to/image.jpg
 */
@SpringBootTest
class RegistrationVehicleOcrManualIntegrationTest {

    @Autowired
    private VehicleOcrService vehicleOcrService;

    @Test
    void runVehicleOcrOnSingleImage() throws Exception {
        String enabledRaw = System.getProperty(
                "runRegistrationVehicleOcrIT",
                System.getenv().getOrDefault("RUN_REGISTRATION_VEHICLE_OCR_IT", "false")
        );
        boolean enabled = Boolean.parseBoolean(enabledRaw);
        Assumptions.assumeTrue(enabled, "manual integration test is disabled");

        String imagePath = System.getProperty(
                "registrationVehicleOcrImage",
                System.getenv().getOrDefault(
                        "REGISTRATION_VEHICLE_OCR_IMAGE",
                        "/Users/inkyung/Downloads/등록증1.jpeg"
                )
        );
        Path image = Path.of(imagePath);
        Assumptions.assumeTrue(Files.exists(image), "image not found: " + image);

        byte[] bytes = Files.readAllBytes(image);
        VehicleRegistration result = vehicleOcrService.processBytes(bytes, image.getFileName().toString());

        System.out.println("[REG-OCR] image=" + image);
        System.out.println("[REG-OCR] vin=" + result.getVin());
        System.out.println("[REG-OCR] vehicleNo=" + result.getVehicleNo());
        System.out.println("[REG-OCR] qualityScore=" + result.getQualityScore());
        System.out.println("[REG-OCR] needsRetry=" + result.getNeedsRetry());
        System.out.println("[REG-OCR] qualityReason=" + result.getQualityReason());
        if (result.getEvidence() != null && result.getEvidence().get("vin") != null) {
            System.out.println("[REG-OCR] vin.conf=" + result.getEvidence().get("vin").getConfidence());
            System.out.println("[REG-OCR] vin.candidates=" + result.getEvidence().get("vin").getCandidates());
        }
    }
}
