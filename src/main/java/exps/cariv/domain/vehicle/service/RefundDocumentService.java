package exps.cariv.domain.vehicle.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.ocr.entity.OcrJobStatus;
import exps.cariv.domain.ocr.entity.OcrParseJob;
import exps.cariv.domain.ocr.repository.OcrParseJobRepository;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.repository.VehicleSpecification;
import exps.cariv.domain.malso.print.XlsxToPdfConverter;
import exps.cariv.global.aws.S3ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 환급 관련서류 ZIP 다운로드 서비스.
 * <p>ZIP 구성 (차량별 폴더):</p>
 * <ul>
 *   <li>차량현황_{월}.xlsx — 해당 월 전체 차량 엑셀</li>
 *   <li>{차량번호}_{차대번호}/매매계약서.pdf — 차량별 매매계약서 PDF</li>
 *   <li>{차량번호}_{차대번호}/말소증.{ext} — 차량별 말소증(S3)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RefundDocumentService {

    private final VehicleRepository vehicleRepo;
    private final VehicleExcelService excelService;
    private final DocumentRepository documentRepo;
    private final S3ObjectReader s3ObjectReader;
    private final OcrParseJobRepository ocrJobRepo;
    private final ObjectMapper objectMapper;
    private final XlsxToPdfConverter pdfConverter;

    private static final String TEMPLATE_PATH = "templates/excel/환급계약서_템플릿.xls";
    private static final String FONT_PATH = "templates/fonts/NanumGothic.ttf";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_KR_FMT = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
    private static final NumberFormat PRICE_FMT = NumberFormat.getNumberInstance(Locale.KOREA);

    /**
     * 환급 관련서류 ZIP 생성.
     * 차량별 폴더 구조로 매매계약서(PDF) + 말소증을 묶는다.
     */
    public byte[] generateRefundDocsZip(Long companyId, YearMonth yearMonth) throws IOException {
        LocalDate from = yearMonth.atDay(1);
        LocalDate to = yearMonth.atEndOfMonth();

        Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                .and(VehicleSpecification.notDeleted())
                .and(VehicleSpecification.purchaseDateAfter(from))
                .and(VehicleSpecification.purchaseDateBefore(to));

        List<Vehicle> vehicles = vehicleRepo.findAll(spec, Sort.by(Sort.Direction.ASC, "purchaseDate"));

        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(zipOut)) {
            // 1. 차량현황 엑셀 (루트에 위치)
            byte[] vehicleExcel = excelService.exportToExcelByPurchaseMonth(companyId, yearMonth);
            addZipEntry(zos, "차량현황_" + yearMonth + ".xlsx", vehicleExcel);

            // 2. 차량별 폴더로 매매계약서 + 말소증 묶기
            for (Vehicle v : vehicles) {
                String folderName = buildFolderName(v);

                // 매매계약서(자동차양도증명서) PDF 생성
                try {
                    byte[] contractPdf = generateContractPdf(v);
                    addZipEntry(zos, folderName + "/자동차양도증명서.pdf", contractPdf);
                } catch (Exception e) {
                    log.warn("매매계약서 PDF 생성 실패: vehicleId={}, error={}", v.getId(), e.getMessage());
                }

                // 말소증 (S3)
                Optional<Document> malsoDoc = documentRepo
                        .findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                                companyId, DocumentRefType.VEHICLE, v.getId(), DocumentType.DEREGISTRATION
                        );
                if (malsoDoc.isPresent()) {
                    byte[] malsoBytes = s3ObjectReader.readBytes(malsoDoc.get().getS3Key());
                    if (malsoBytes != null) {
                        String ext = guessExtension(malsoDoc.get().getOriginalFilename());
                        addZipEntry(zos, folderName + "/말소증" + ext, malsoBytes);
                    }
                }

                // 신분증 (S3)
                Optional<Document> idCardDoc = documentRepo
                        .findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                                companyId, DocumentRefType.VEHICLE, v.getId(), DocumentType.ID_CARD
                        );
                if (idCardDoc.isPresent()) {
                    byte[] idCardBytes = s3ObjectReader.readBytes(idCardDoc.get().getS3Key());
                    if (idCardBytes != null) {
                        String ext = guessExtension(idCardDoc.get().getOriginalFilename());
                        addZipEntry(zos, folderName + "/신분증" + ext, idCardBytes);
                    }
                }

                // 사업자등록증 (S3)
                Optional<Document> bizDoc = documentRepo
                        .findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                                companyId, DocumentRefType.VEHICLE, v.getId(), DocumentType.BIZ_REGISTRATION
                        );
                if (bizDoc.isPresent()) {
                    byte[] bizBytes = s3ObjectReader.readBytes(bizDoc.get().getS3Key());
                    if (bizBytes != null) {
                        String ext = guessExtension(bizDoc.get().getOriginalFilename());
                        addZipEntry(zos, folderName + "/사업자등록증" + ext, bizBytes);
                    }
                }
            }
        }

        return zipOut.toByteArray();
    }

    // ─── 차량 폴더명 생성 ───

    private String buildFolderName(Vehicle v) {
        String vehicleNo = safe(v.getVehicleNo()).replaceAll("[/\\\\:*?\"<>|]", "_");
        String vin = safe(v.getVin()).replaceAll("[/\\\\:*?\"<>|]", "_");
        if (vehicleNo.isEmpty() && vin.isEmpty()) {
            return "차량_" + v.getId();
        }
        if (vehicleNo.isEmpty()) return vin;
        if (vin.isEmpty()) return vehicleNo;
        return vehicleNo + "_" + vin;
    }

    // ─── 자동차양도증명서 PDF 생성 ───

    /**
     * 자동차양도증명서(별지 16호 서식) PDF 생성.
     * <ol>
     *   <li>입력시트에 값 세팅 → 계약서 시트 수식이 자동 참조</li>
     *   <li>FormulaEvaluator로 수식 평가 후 값 고정</li>
     *   <li>입력시트/Sheet1 숨김, 2~5페이지 삭제</li>
     *   <li>LibreOffice로 PDF 변환 (A4 1페이지)</li>
     * </ol>
     */
    private byte[] generateContractPdf(Vehicle v) throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);

        byte[] xlsBytes;
        try (InputStream is = resource.getInputStream();
             HSSFWorkbook wb = new HSSFWorkbook(is)) {

            // 소유자 OCR 정보 가져오기
            String ocrIdNumber = "";
            String ocrAddress = "";
            String ocrHolderName = "";
            try {
                Optional<Document> idCardDoc = documentRepo
                        .findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                                v.getCompanyId(), DocumentRefType.VEHICLE, v.getId(), DocumentType.ID_CARD);
                if (idCardDoc.isPresent()) {
                    Optional<OcrParseJob> job = ocrJobRepo.findTopByCompanyIdAndVehicleDocumentIdAndStatusOrderByCreatedAtDesc(
                            v.getCompanyId(), idCardDoc.get().getId(), OcrJobStatus.SUCCEEDED);
                    if (job.isPresent() && job.get().getResultJson() != null) {
                        JsonNode root = objectMapper.readTree(job.get().getResultJson());
                        JsonNode parsed = root.path("parsed");
                        if (parsed.isMissingNode() || !parsed.isObject()) parsed = root;
                        ocrIdNumber = safe(parsed.path("idNumber").asText(null));
                        ocrAddress = safe(parsed.path("idAddress").asText(null));
                        ocrHolderName = safe(parsed.path("holderName").asText(null));
                    }
                }
            } catch (Exception ignored) {}

            String ownerName = ocrHolderName.isBlank() ? safe(v.getOwnerName()) : ocrHolderName;
            String idNumber = ocrIdNumber.isBlank() ? safe(v.getOwnerId()) : ocrIdNumber;
            String carTypeYear = safe(v.getCarType());
            if (v.getModelYear() != null) carTypeYear += "(" + v.getModelYear() + ")";
            String priceStr = (v.getPurchasePrice() != null && v.getPurchasePrice() > 0)
                    ? PRICE_FMT.format(v.getPurchasePrice()) : "";

            // 날짜: 오늘 날짜 사용
            String dateStr = LocalDate.now().format(DATE_FMT);

            // --- 1) 입력시트(Sheet 0)에 값 세팅 → 계약서 수식이 이 값을 참조 ---
            Sheet inputSheet = wb.getSheetAt(0);
            setCellValue(inputSheet, 2, 1, dateStr);            // 날짜
            setCellValue(inputSheet, 3, 1, ownerName);           // 양도인
            setCellValue(inputSheet, 4, 1, safe(v.getVehicleNo())); // 자동차등록번호
            setCellValue(inputSheet, 5, 1, carTypeYear);         // 차종(연식)
            setCellValue(inputSheet, 6, 1, safe(v.getModelName())); // 차명
            setCellValue(inputSheet, 7, 1, safe(v.getVin()));    // 차대번호
            setCellValue(inputSheet, 8, 1, priceStr);            // 매매금액
            setCellValue(inputSheet, 9, 1, idNumber);            // 주민등록번호
            setCellValue(inputSheet, 10, 1, ocrAddress);         // 주소

            // --- 2) 수식 평가 후 값으로 고정 ---
            org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator evaluator =
                    new org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator(wb);
            Sheet contractSheet = wb.getSheet("계약서");
            if (contractSheet == null) contractSheet = wb.getSheetAt(1);

            // 계약서 시트의 모든 수식 셀을 평가하고 값으로 대체
            for (int r = 0; r <= 45; r++) {
                Row row = contractSheet.getRow(r);
                if (row == null) continue;
                for (Cell cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        try {
                            org.apache.poi.ss.usermodel.CellValue cv = evaluator.evaluate(cell);
                            if (cv != null) {
                                switch (cv.getCellType()) {
                                    case STRING:
                                        cell.setCellValue(cv.getStringValue());
                                        break;
                                    case NUMERIC:
                                        // 숫자 → 문자열로 변환 (서식 유지)
                                        String formatted = cv.getNumberValue() == 0.0 ? ""
                                                : String.valueOf((long) cv.getNumberValue());
                                        cell.setCellValue(formatted);
                                        break;
                                    default:
                                        cell.setCellValue("");
                                        break;
                                }
                            }
                        } catch (Exception e) {
                            // 수식 평가 실패 시 빈 값으로 대체
                            cell.setCellValue("");
                        }
                    }
                }
            }

            // --- 3) 2~5페이지 삭제 (Row 46~229) ---
            int lastRow = contractSheet.getLastRowNum();
            for (int r = lastRow; r >= 46; r--) {
                Row row = contractSheet.getRow(r);
                if (row != null) contractSheet.removeRow(row);
            }

            // --- 4) 입력시트/Sheet1 완전 삭제 (숨김이 아니라 삭제) ---
            // 계약서 시트만 남기고 나머지 모두 삭제 (역순으로)
            int contractIdx = wb.getSheetIndex("계약서");
            for (int i = wb.getNumberOfSheets() - 1; i >= 0; i--) {
                if (i != contractIdx) {
                    wb.removeSheetAt(i);
                }
            }
            // 삭제 후 계약서가 유일한 시트 (index 0)
            wb.setActiveSheet(0);

            // --- 5) A4 1페이지 인쇄 설정 ---
            Sheet onlySheet = wb.getSheetAt(0);
            wb.setPrintArea(0, 0, 11, 0, 45);
            onlySheet.setFitToPage(true);
            onlySheet.getPrintSetup().setFitWidth((short) 1);
            onlySheet.getPrintSetup().setFitHeight((short) 1);
            onlySheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            onlySheet.getPrintSetup().setLandscape(false);
            // 마진을 최소로 (0.05인치) → LibreOffice가 A4 안에 확실히 맞추도록
            onlySheet.setMargin(Sheet.TopMargin, 0.05);
            onlySheet.setMargin(Sheet.BottomMargin, 0.05);
            onlySheet.setMargin(Sheet.LeftMargin, 0.05);
            onlySheet.setMargin(Sheet.RightMargin, 0.05);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            xlsBytes = out.toByteArray();
        }

        // XLS → PDF 변환 (LibreOffice headless)
        return pdfConverter.convertXls(xlsBytes);
    }

    // ─── 유틸 ───

    private void setCellValue(Sheet sheet, int rowIdx, int colIdx, String value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    private void setCellValue(Sheet sheet, int rowIdx, int colIdx, long value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private String formatDate(LocalDate date) {
        return date != null ? date.format(DATE_FMT) : "";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String guessExtension(String filename) {
        if (filename == null) return ".pdf";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        return ".pdf";
    }
}
