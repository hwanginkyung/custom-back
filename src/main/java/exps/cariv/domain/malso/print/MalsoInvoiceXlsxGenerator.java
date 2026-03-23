package exps.cariv.domain.malso.print;

import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.global.parser.KoreanEnglishConverter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 말소 신청용 간이 Invoice/Packing List XLSX 생성기.
 * 템플릿(templates/invoice_malso.xlsx)에 더미값을 채우고 차량 정보만 실제 값으로 반영합니다.
 */
@Component
public class MalsoInvoiceXlsxGenerator {

    private static final String TEMPLATE = "templates/invoice_malso.xlsx";

    // 기본 헤더/더미값 셀
    private static final String EXPORTER_INFO_CELL = "A3";
    private static final String INVOICE_NO_CELL = "E3";
    private static final String PAYMENT_TERMS_CELL = "E6";
    private static final String CONSIGNEE_CELL = "A9";
    private static final String NOTIFY_PARTY_CELL = "A13";
    private static final String PORT_OF_LOADING_CELL = "A16";
    private static final String FINAL_DESTINATION_CELL = "C16";
    private static final String VESSEL_NAME_CELL = "A18";
    private static final String SAILING_DATE_CELL = "C18";
    private static final String GOODS_DESC_CELL = "B20";
    private static final String DELIVERY_TERMS_CELL = "E20";
    private static final String SHIPPING_METHOD_CELL = "D30";

    // 차량 품목(1행)
    private static final String ITEM_MODEL_CELL = "B23";
    private static final String ITEM_YEAR_CELL = "C23";
    private static final String ITEM_VIN_CELL = "D23";
    private static final String ITEM_QTY_CELL = "E23";
    private static final String ITEM_UNIT_PRICE_CELL = "F23";
    private static final String ITEM_AMOUNT_CELL = "G23";
    private static final String ITEM_WEIGHT_CELL = "H23";
    private static final String ITEM_CBM_CELL = "I23";

    // 합계
    private static final String SUB_TOTAL_CELL = "D31";
    private static final String OCEAN_FREIGHT_CELL = "E32";
    private static final String COLLECTED_CELL = "D33";
    private static final String GRAND_TOTAL_AMOUNT_CELL = "G34";
    private static final String GRAND_TOTAL_WEIGHT_CELL = "H34";
    private static final String GRAND_TOTAL_CBM_CELL = "I34";
    private static final String SIGNED_BY_CELL = "E35";

    // 간이 문서 고정값
    private static final String DEFAULT_EXPORTER_INFO = "CARIV EXPORT\nSeoul, Republic of Korea\nTel : N/A";
    private static final String DEFAULT_PAYMENT_TERMS = "*T/T BASE";
    private static final String DEFAULT_CONSIGNEE = "DEREGISTRATION TEST CONSIGNEE";
    private static final String DEFAULT_NOTIFY_PARTY = "SAME AS CONSIGNEE";
    private static final String DEFAULT_PORT_OF_LOADING = "INCHEON PORT";
    private static final String DEFAULT_FINAL_DESTINATION = "N/A";
    private static final String DEFAULT_VESSEL_NAME = "RORO VESSEL";
    private static final String DEFAULT_GOODS_DESC = "KOREAN USED CAR";
    private static final String DEFAULT_DELIVERY_TERMS = "CFR INCHEON, KOREA";
    private static final String DEFAULT_SIGNED_BY = "CARIV EXPORT";
    private static final long DEFAULT_UNIT_PRICE = 1000L;
    private static final long DEFAULT_WEIGHT_KG = 1500L;
    private static final double DEFAULT_CBM = 10.0;

    public byte[] generate(Vehicle vehicle) {
        Vehicle source = vehicle == null ? Vehicle.builder().build() : vehicle;

        String modelName = valueOrDash(KoreanEnglishConverter.toEnglishVehicleName(source.getModelName()));
        String modelYear = source.getModelYear() == null ? "-" : String.valueOf(source.getModelYear());
        String vin = valueOrDash(source.getVin());

        long unitPrice = positiveOrDefault(source.getPurchasePrice(), DEFAULT_UNIT_PRICE);
        long amount = unitPrice; // 간이 문서: Q'ty 1 고정
        long weightKg = DEFAULT_WEIGHT_KG;
        double cbm = DEFAULT_CBM;

        String finalDestination = DEFAULT_FINAL_DESTINATION;
        String signedBy = valueOrDefault(KoreanEnglishConverter.toEnglish(source.getShipperName()), DEFAULT_SIGNED_BY);
        String shippingMethod = valueOrDefault(source.getShippingMethod(), "RORO").toUpperCase();

        try (InputStream is = new ClassPathResource(TEMPLATE).getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(is);
             ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            Sheet sheet = wb.getSheetAt(0);

            setString(sheet, EXPORTER_INFO_CELL, DEFAULT_EXPORTER_INFO);
            setString(sheet, INVOICE_NO_CELL, buildInvoiceNo(source));
            setString(sheet, PAYMENT_TERMS_CELL, DEFAULT_PAYMENT_TERMS);
            setString(sheet, CONSIGNEE_CELL, DEFAULT_CONSIGNEE);
            setString(sheet, NOTIFY_PARTY_CELL, DEFAULT_NOTIFY_PARTY);
            setString(sheet, PORT_OF_LOADING_CELL, DEFAULT_PORT_OF_LOADING);
            setString(sheet, FINAL_DESTINATION_CELL, finalDestination);
            setString(sheet, VESSEL_NAME_CELL, DEFAULT_VESSEL_NAME);
            setString(sheet, SAILING_DATE_CELL, LocalDate.now().plusDays(7).toString());
            setString(sheet, GOODS_DESC_CELL, DEFAULT_GOODS_DESC);
            setString(sheet, DELIVERY_TERMS_CELL, DEFAULT_DELIVERY_TERMS);
            setString(sheet, SHIPPING_METHOD_CELL, shippingMethod);

            setString(sheet, ITEM_MODEL_CELL, modelName);
            setString(sheet, ITEM_YEAR_CELL, modelYear);
            setString(sheet, ITEM_VIN_CELL, vin);
            setNumeric(sheet, ITEM_QTY_CELL, 1d);
            setNumeric(sheet, ITEM_UNIT_PRICE_CELL, unitPrice);
            setNumeric(sheet, ITEM_AMOUNT_CELL, amount);
            setNumeric(sheet, ITEM_WEIGHT_CELL, weightKg);
            setNumeric(sheet, ITEM_CBM_CELL, cbm);

            setNumeric(sheet, SUB_TOTAL_CELL, amount);
            setNumeric(sheet, OCEAN_FREIGHT_CELL, 0d);
            setNumeric(sheet, COLLECTED_CELL, amount);
            setNumeric(sheet, GRAND_TOTAL_AMOUNT_CELL, amount);
            setNumeric(sheet, GRAND_TOTAL_WEIGHT_CELL, weightKg);
            setNumeric(sheet, GRAND_TOTAL_CBM_CELL, cbm);
            setString(sheet, SIGNED_BY_CELL, "Signed By    " + signedBy);

            wb.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate invoice xlsx", e);
        }
    }

    /**
     * 구버전 시그니처 호환.
     */
    public byte[] generate(String modelName, String vin) {
        Vehicle vehicle = Vehicle.builder()
                .modelName(modelName)
                .vin(vin)
                .build();
        return generate(vehicle);
    }

    private static String buildInvoiceNo(Vehicle vehicle) {
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = vehicle != null && vehicle.getId() != null
                ? String.valueOf(vehicle.getId())
                : "TEMP";
        return "INV-" + datePart + "-" + suffix;
    }

    private static String valueOrDash(String value) {
        return isBlank(value) ? "-" : value.trim();
    }

    private static String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static long positiveOrDefault(Long value, long fallback) {
        return (value != null && value > 0) ? value : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void setString(Sheet sheet, String addr, String value) {
        CellReference ref = new CellReference(addr);
        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());
        if (cell.getCellType() == CellType.FORMULA) {
            cell.setCellFormula(null);
        }
        cell.setCellValue(value);
    }

    private static void setNumeric(Sheet sheet, String addr, double value) {
        CellReference ref = new CellReference(addr);
        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());
        if (cell.getCellType() == CellType.FORMULA) {
            cell.setCellFormula(null);
        }
        cell.setCellValue(value);
    }
}
