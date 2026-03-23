package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.entity.ContainerInfo;
import exps.cariv.domain.customs.entity.CustomsRequestVehicle;
import exps.cariv.domain.customs.entity.ShippingMethod;
import exps.cariv.domain.customs.entity.TradeCondition;
import exps.cariv.domain.vehicle.entity.Vehicle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CustomsInvoiceXlsxGeneratorTest {

    private final CustomsInvoiceXlsxGenerator generator = new CustomsInvoiceXlsxGenerator();
    private final DataFormatter formatter = new DataFormatter();
    private static final Pattern HANGUL_PATTERN = Pattern.compile(".*[가-힣]+.*");

    @Test
    void generateMulti_usesRoroTemplateAndFillsRows() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(101L)
                .modelName("Sonata")
                .vin("KMHE1234567890123")
                .modelYear(2021)
                .fuelType("Gasoline")
                .displacement(1999)
                .shipperName("ForwardMax")
                .build();

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(1L)
                .price(8300L)
                .tradeCondition(TradeCondition.FOB)
                .build();

        byte[] xlsx = generator.generateMulti(List.of(vehicle), List.of(crv), null);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            var sheet = wb.getSheetAt(0);

            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(0))).containsIgnoringCase("Ro-Ro");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(1))).isEqualTo("Usedcar");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(3))).isEqualTo("Sonata");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(7))).isEqualTo("KMHE1234567890123");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(15))).isEqualTo("8703239090");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(21))).isEqualTo("8300");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(24))).isEqualTo("8300");
        }
    }

    @Test
    void generateMulti_usesContainerTemplateAndMapsContainerFields() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(102L)
                .modelName("K5")
                .vin("KNAGH412345678901")
                .modelYear(2020)
                .fuelType("Diesel")
                .displacement(2199)
                .shipperName("ForwardMax")
                .build();

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(2L)
                .price(9100L)
                .tradeCondition(TradeCondition.CIF)
                .build();

        ContainerInfo ci = ContainerInfo.builder()
                .containerNo("MSCU1234567")
                .sealNo("SEAL-0099")
                .vesselName("Belief Yard")
                .entryPort("HIT")
                .exportPort("Incheon")
                .destinationCountry("KG")
                .consignee("Demo Consignee")
                .build();

        byte[] xlsx = generator.generateMulti(
                List.of(vehicle),
                List.of(crv),
                ci,
                ShippingMethod.CONTAINER,
                new CustomsInvoiceXlsxGenerator.ShipperInfo(
                        "ForwardMax Inc.",
                        "Seoul, Korea",
                        "010-1234-5678"
                ),
                null,
                Map.of(102L, new BigDecimal("9.876")),
                Map.of(102L, new BigDecimal("1540"))
        );

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            var sheet = wb.getSheetAt(0);

            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(0))).containsIgnoringCase("Container");
            assertThat(formatter.formatCellValue(sheet.getRow(4).getCell(2))).isEqualTo("ForwardMax Inc.");
            assertThat(formatter.formatCellValue(sheet.getRow(5).getCell(2))).isEqualTo("Seoul, Korea");
            assertThat(formatter.formatCellValue(sheet.getRow(8).getCell(2))).isEqualTo("010-1234-5678");
            assertThat(formatter.formatCellValue(sheet.getRow(4).getCell(18))).isEqualTo("South Korea");
            assertThat(formatter.formatCellValue(sheet.getRow(6).getCell(18))).isEqualTo("Incheon");
            assertThat(formatter.formatCellValue(sheet.getRow(8).getCell(18))).isEqualTo("KG");
            assertThat(formatter.formatCellValue(sheet.getRow(12).getCell(4))).isEqualTo("MSCU1234567");
            assertThat(formatter.formatCellValue(sheet.getRow(12).getCell(13))).isEqualTo("SEAL-0099");
            assertThat(formatter.formatCellValue(sheet.getRow(13).getCell(4))).isEqualTo("Belief Yard");
            assertThat(formatter.formatCellValue(sheet.getRow(13).getCell(13))).isEqualTo("HIT");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(1))).isEqualTo("Usedcar");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(3))).isEqualTo("K5");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(15))).isEqualTo("8703329090");
            assertThat(sheet.getRow(16).getCell(17).getNumericCellValue()).isEqualTo(1540d);
            assertThat(sheet.getRow(16).getCell(18).getNumericCellValue()).isEqualTo(1540d);
            assertThat(sheet.getRow(16).getCell(19).getNumericCellValue()).isEqualTo(9.876d);
            assertThat(sheet.getRow(16).getCell(23).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(16).getCell(24).getNumericCellValue()).isEqualTo(9100d);
            assertThat(sheet.getRow(26).getCell(17).getNumericCellValue()).isEqualTo(1540d);
            assertThat(sheet.getRow(26).getCell(18).getNumericCellValue()).isEqualTo(1540d);
            assertThat(sheet.getRow(26).getCell(19).getNumericCellValue()).isEqualTo(9.876d);
            assertThat(sheet.getRow(26).getCell(23).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(26).getCell(24).getNumericCellValue()).isEqualTo(9100d);
        }
    }

    @Test
    void generateMulti_convertsKoreanFieldsToEnglishText() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(103L)
                .modelName("투싼(TUCSON)")
                .vin("KMHJN81VP8U901614")
                .modelYear(2008)
                .fuelType("경유")
                .displacement(1991)
                .shipperName("세원무역")
                .build();

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(103L)
                .price(8900L)
                .tradeCondition(TradeCondition.CFR)
                .build();

        ContainerInfo ci = ContainerInfo.builder()
                .exportPort("인천항")
                .destinationCountry("가나")
                .consignee("(주)해피카")
                .warehouseLocation("경기도 화성시 장안면")
                .build();

        byte[] xlsx = generator.generateMulti(
                List.of(vehicle),
                List.of(crv),
                ci,
                ShippingMethod.RORO,
                new CustomsInvoiceXlsxGenerator.ShipperInfo(
                        "세원무역",
                        "서울특별시 강남구 테헤란로 100",
                        "010-1234-5678"
                ),
                null,
                Map.of(103L, new BigDecimal("10.123")),
                Map.of(103L, new BigDecimal("1805")),
                "경기도 화성시 장안면 포승장안로 922"
        );

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);

            String shipperName = formatter.formatCellValue(sheet.getRow(4).getCell(2));
            String shipperAddress = formatter.formatCellValue(sheet.getRow(5).getCell(2));
            String modelName = formatter.formatCellValue(sheet.getRow(16).getCell(3));
            String fuel = formatter.formatCellValue(sheet.getRow(16).getCell(11));

            assertThat(HANGUL_PATTERN.matcher(shipperName).matches()).isFalse();
            assertThat(HANGUL_PATTERN.matcher(shipperAddress).matches()).isFalse();
            assertThat(modelName).isEqualTo("TUCSON");
            assertThat(fuel).isEqualTo("Diesel");
        }
    }

    @Test
    void generateMulti_fillsCbmWhenProvided() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(500L)
                .modelName("Carnival")
                .vin("KNABC123456789012")
                .modelYear(2022)
                .fuelType("Diesel")
                .displacement(2151)
                .build();

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(500L)
                .price(12000L)
                .tradeCondition(TradeCondition.FOB)
                .build();

        byte[] xlsx = generator.generateMulti(
                List.of(vehicle),
                List.of(crv),
                null,
                ShippingMethod.RORO,
                Map.of(500L, new BigDecimal("12.345"))
        );

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(16).getCell(19).getNumericCellValue()).isEqualTo(12.345d);
        }
    }

    @Test
    void generateMulti_roroWritesWarehouseLocationWhenProvided() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(700L)
                .modelName("Avante")
                .vin("KMHZZZ12345678901")
                .modelYear(2019)
                .fuelType("Gasoline")
                .displacement(1598)
                .build();

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(700L)
                .price(5500L)
                .tradeCondition(TradeCondition.FOB)
                .build();

        byte[] xlsx = generator.generateMulti(
                List.of(vehicle),
                List.of(crv),
                null,
                ShippingMethod.RORO,
                new CustomsInvoiceXlsxGenerator.ShipperInfo("ForwardMax", "Seoul", "010-9999-1111"),
                null,
                Map.of(),
                Map.of(),
                "Incheon Yard"
        );

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(formatter.formatCellValue(sheet.getRow(12).getCell(0))).isEqualTo("Incheon Yard");
        }
    }

    @Test
    void generateMulti_usesVehiclePurchasePriceWhenItemPriceMissing() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(701L)
                .modelName("SM6")
                .vin("KNASZZ12345678901")
                .modelYear(2018)
                .fuelType("Gasoline")
                .displacement(1998)
                .purchasePrice(6400L)
                .build();

        CustomsRequestVehicle crv = CustomsRequestVehicle.builder()
                .vehicleId(701L)
                .price(null)
                .tradeCondition(TradeCondition.FOB)
                .build();

        byte[] xlsx = generator.generateMulti(
                List.of(vehicle),
                List.of(crv),
                null,
                ShippingMethod.RORO
        );

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(21))).isEqualTo("6400");
            assertThat(formatter.formatCellValue(sheet.getRow(16).getCell(24))).isEqualTo("6400");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "INVOICE_SAMPLE_EXPORT", matches = "true")
    void exportSampleFiles_forManualReview() throws IOException {
        Path sampleDir = Path.of("docs", "samples");
        Files.createDirectories(sampleDir);

        Vehicle containerVehicle = Vehicle.builder()
                .id(9001L)
                .modelName("Kia Sorento")
                .vin("KNAXXXXXXXXXXXXX1")
                .modelYear(2021)
                .fuelType("Diesel")
                .displacement(2199)
                .build();
        CustomsRequestVehicle containerCrv = CustomsRequestVehicle.builder()
                .vehicleId(9001L)
                .price(12800L)
                .tradeCondition(TradeCondition.CIF)
                .build();
        ContainerInfo ci = ContainerInfo.builder()
                .containerNo("MSCU1234567")
                .sealNo("SEAL-7788")
                .entryPort("ICD-Busan")
                .vesselName("Ocean Forwarder")
                .exportPort("Busan")
                .destinationCountry("Tanzania")
                .consignee("Global Trade Ltd.")
                .build();
        byte[] containerXlsx = generator.generateMulti(
                List.of(containerVehicle),
                List.of(containerCrv),
                ci,
                ShippingMethod.CONTAINER,
                new CustomsInvoiceXlsxGenerator.ShipperInfo(
                        "ForwardMax Co., Ltd.",
                        "123 Teheran-ro, Gangnam-gu, Seoul, Korea",
                        "+82-2-1234-5678"
                ),
                null,
                Map.of(9001L, new BigDecimal("13.200")),
                Map.of(9001L, new BigDecimal("1860")),
                null
        );
        Path containerFile = sampleDir.resolve("invoice_container_dummy.xlsx");
        Files.write(containerFile, containerXlsx);

        Vehicle roroVehicle = Vehicle.builder()
                .id(9002L)
                .modelName("Hyundai Avante")
                .vin("KMHYYYYYYYYYYYYY2")
                .modelYear(2019)
                .fuelType("Gasoline")
                .displacement(1598)
                .purchasePrice(7400L)
                .build();
        CustomsRequestVehicle roroCrv = CustomsRequestVehicle.builder()
                .vehicleId(9002L)
                .price(null)
                .tradeCondition(TradeCondition.FOB)
                .build();
        byte[] roroXlsx = generator.generateMulti(
                List.of(roroVehicle),
                List.of(roroCrv),
                null,
                ShippingMethod.RORO,
                new CustomsInvoiceXlsxGenerator.ShipperInfo(
                        "ForwardMax Co., Ltd.",
                        "123 Teheran-ro, Gangnam-gu, Seoul, Korea",
                        "+82-2-1234-5678"
                ),
                null,
                Map.of(9002L, new BigDecimal("10.950")),
                Map.of(9002L, new BigDecimal("1420")),
                "Busan Port Warehouse"
        );
        Path roroFile = sampleDir.resolve("invoice_roro_dummy.xlsx");
        Files.write(roroFile, roroXlsx);

        assertThat(Files.exists(containerFile)).isTrue();
        assertThat(Files.size(containerFile)).isGreaterThan(0L);
        assertThat(Files.exists(roroFile)).isTrue();
        assertThat(Files.size(roroFile)).isGreaterThan(0L);
    }
}
