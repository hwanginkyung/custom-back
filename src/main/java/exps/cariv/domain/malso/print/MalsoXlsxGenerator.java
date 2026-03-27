package exps.cariv.domain.malso.print;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static exps.cariv.domain.malso.print.MalsoTemplateSpec.*;

/**
 * 말소등록신청서 XLSX 생성기.
 * classpath 템플릿(templates/말소신청서_A4_수정본_v3.xlsx)에 데이터를 채워 반환합니다.
 */
@Component
public class MalsoXlsxGenerator {

    private static final String TEMPLATE = "templates/말소신청서_A4_수정본_v3.xlsx";
    private static final DateTimeFormatter APP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy  년  MM  월  dd  일");
    // 출력 시작점은 좌상단(A1)로 고정한다.
    // 인쇄영역은 템플릿 정의값을 우선 사용하고, 없으면 기본값(A1:K37)을 적용한다.
    private static final int PRINT_COL_FROM = 0;   // A
    private static final int DEFAULT_PRINT_COL_TO = 10; // K
    private static final int PRINT_ROW_FROM = 0;   // 1
    private static final int DEFAULT_PRINT_ROW_TO = 36; // 37

    public byte[] generate(MalsoXlsxData data) {
        try (InputStream is = new ClassPathResource(TEMPLATE).getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(is);
             ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            Sheet sheet = wb.getSheet("신청서");
            if (sheet == null) sheet = wb.getSheetAt(0);

            // 1) 소유자
            setString(sheet, OWNER_NAME, data.ownerName());
            setString(sheet, OWNER_IDNO, data.ownerIdNo());
            setString(sheet, OWNER_ADDR, data.ownerAddress());

            // 2) 차량
            setString(sheet, VEHICLE_REG_NO, data.vehicleRegistrationNo());
            setString(sheet, VEHICLE_CHASSIS, data.vehicleChassisNo());
            setMileage(sheet, VEHICLE_MILEAGE, data.vehicleMileage());

            // 3) 신청인 + 신청일
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            setString(sheet, APPLICATION_DATE, APP_DATE_FORMAT.format(today));
            setString(sheet, APPLICANT_NAME, data.applicantName());
            setString(sheet, APPLICANT_BIRTH, data.applicantBirthDate());

            // 4) 위임장
            setString(sheet, POA_NAME, data.poaName());
            setString(sheet, POA_REP_NAME, data.poaRepresentativeName());
            setString(sheet, POA_BIZNO, data.poaBizNo());
            setString(sheet, POA_ADDR, data.poaAddress());

            applyPrintLayout(wb, sheet);

            wb.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate malso xlsx", e);
        }
    }

    // -------- helpers --------

    private static void setString(Sheet sheet, String addr, String value) {
        Cell cell = cell(sheet, addr);
        if (value == null) value = "";
        cell.setCellValue(value);
    }

    private static void setMileage(Sheet sheet, String addr, Long value) {
        if (value == null) {
            setString(sheet, addr, "");
            return;
        }
        setString(sheet, addr, value + " KM");
    }

    private static Cell cell(Sheet sheet, String addr) {
        CellReference ref = new CellReference(addr);
        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());
        return cell;
    }

    private static void applyPrintLayout(XSSFWorkbook wb, Sheet sheet) {
        PrintSetup printSetup = sheet.getPrintSetup();
        printSetup.setPaperSize(PrintSetup.A4_PAPERSIZE);
        printSetup.setLandscape(false);
        printSetup.setFitWidth((short) 1);
        printSetup.setFitHeight((short) 1);
        printSetup.setScale((short) 100);

        sheet.setFitToPage(true);
        sheet.setAutobreaks(true);
        sheet.setHorizontallyCenter(true);
        int sheetIndex = wb.getSheetIndex(sheet);
        String existingPrintArea = wb.getPrintArea(sheetIndex);
        if (existingPrintArea == null || existingPrintArea.isBlank()) {
            wb.setPrintArea(
                    sheetIndex,
                    PRINT_COL_FROM,
                    DEFAULT_PRINT_COL_TO,
                    PRINT_ROW_FROM,
                    DEFAULT_PRINT_ROW_TO
            );
        }
    }

}
