package exps.cariv.domain.malso.print;

import exps.cariv.domain.vehicle.entity.Vehicle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class MalsoInvoiceXlsxGeneratorTest {

    private final MalsoInvoiceXlsxGenerator generator = new MalsoInvoiceXlsxGenerator();
    private final DataFormatter formatter = new DataFormatter();

    @Test
    void generate_populatesDummyFieldsAndVehicleInfo() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(6L)
                .modelName("MORNING")
                .modelYear(2018)
                .vin("KNAG6412BNAN193175")
                .purchasePrice(7400L)
                .shipperName("CARIV EXPORT")
                .shippingMethod("CONTAINER")
                .build();

        byte[] xlsx = generator.generate(vehicle);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);

            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(4))).startsWith("INV-");
            assertThat(formatter.formatCellValue(sheet.getRow(8).getCell(0))).isEqualTo("DEREGISTRATION TEST CONSIGNEE");
            assertThat(formatter.formatCellValue(sheet.getRow(15).getCell(2))).isEqualTo("N/A");

            assertThat(formatter.formatCellValue(sheet.getRow(22).getCell(1))).isEqualTo("MORNING");
            assertThat(formatter.formatCellValue(sheet.getRow(22).getCell(2))).isEqualTo("2018");
            assertThat(formatter.formatCellValue(sheet.getRow(22).getCell(3))).isEqualTo("KNAG6412BNAN193175");
            assertThat(sheet.getRow(22).getCell(4).getNumericCellValue()).isEqualTo(1d);
            assertThat(sheet.getRow(22).getCell(5).getNumericCellValue()).isEqualTo(7400d);
            assertThat(sheet.getRow(22).getCell(6).getNumericCellValue()).isEqualTo(7400d);

            assertThat(formatter.formatCellValue(sheet.getRow(29).getCell(3))).isEqualTo("CONTAINER");
            assertThat(sheet.getRow(30).getCell(3).getNumericCellValue()).isEqualTo(7400d);
            assertThat(sheet.getRow(33).getCell(6).getNumericCellValue()).isEqualTo(7400d);
            assertThat(formatter.formatCellValue(sheet.getRow(34).getCell(4))).isEqualTo("Signed By    CARIV EXPORT");
        }
    }

    @Test
    void generate_usesDefaultWhenOptionalValuesMissing() throws IOException {
        Vehicle vehicle = Vehicle.builder()
                .id(7L)
                .modelName("K5")
                .modelYear(2020)
                .vin("KNARZZZZZZZZZZZZ1")
                .build();

        byte[] xlsx = generator.generate(vehicle);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);

            assertThat(formatter.formatCellValue(sheet.getRow(15).getCell(2))).isEqualTo("N/A");
            assertThat(formatter.formatCellValue(sheet.getRow(29).getCell(3))).isEqualTo("RORO");
            assertThat(sheet.getRow(22).getCell(5).getNumericCellValue()).isEqualTo(1000d);
            assertThat(sheet.getRow(33).getCell(6).getNumericCellValue()).isEqualTo(1000d);
            assertThat(formatter.formatCellValue(sheet.getRow(34).getCell(4))).isEqualTo("Signed By    CARIV EXPORT");
        }
    }

    @Test
    void generate_legacySignatureUsesDashWhenYearMissing() throws IOException {
        byte[] xlsx = generator.generate("K5", "KNARZZZZZZZZZZZZ1");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);

            assertThat(formatter.formatCellValue(sheet.getRow(22).getCell(1))).isEqualTo("K5");
            assertThat(formatter.formatCellValue(sheet.getRow(22).getCell(2))).isEqualTo("-");
            assertThat(formatter.formatCellValue(sheet.getRow(22).getCell(3))).isEqualTo("KNARZZZZZZZZZZZZ1");
        }
    }
}
