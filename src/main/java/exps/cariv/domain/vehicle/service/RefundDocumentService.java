package exps.cariv.domain.vehicle.service;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.repository.VehicleSpecification;
import exps.cariv.global.aws.S3ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
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

    private static final String TEMPLATE_PATH = "templates/excel/매매계약서_템플릿.xls";
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

                // 매매계약서 PDF 생성
                try {
                    byte[] contractPdf = generateContractPdf(v);
                    addZipEntry(zos, folderName + "/매매계약서.pdf", contractPdf);
                } catch (Exception e) {
                    log.warn("매매계약서 PDF 생성 실패: vehicleId={}, error={}", v.getId(), e.getMessage());
                    // fallback: 엑셀 버전이라도 넣기
                    try {
                        byte[] contractXls = generateContractExcel(v);
                        addZipEntry(zos, folderName + "/매매계약서.xls", contractXls);
                    } catch (Exception ex) {
                        log.warn("매매계약서 엑셀 fallback도 실패: vehicleId={}", v.getId());
                    }
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

    // ─── 매매계약서 PDF 생성 (PDFBox) ───

    /**
     * 매매계약서를 A4 1장 PDF로 생성.
     * 계약서 본문(당사자 정보, 차량 정보, 계약 조건)만 담는다.
     */
    private byte[] generateContractPdf(Vehicle v) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDType0Font font;
            try (InputStream fontIs = new ClassPathResource(FONT_PATH).getInputStream()) {
                font = PDType0Font.load(doc, fontIs);
            } catch (Exception e) {
                log.warn("한글 폰트 로드 실패, 시스템 폰트 fallback 시도: {}", e.getMessage());
                // 폰트가 없으면 엑셀 fallback으로 던진다
                throw new IOException("한글 폰트(NanumGothic.ttf) 필요: " + e.getMessage(), e);
            }

            float pageWidth = PDRectangle.A4.getWidth();
            float margin = 50;
            float contentWidth = pageWidth - 2 * margin;
            float y = PDRectangle.A4.getHeight() - margin;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // ── 제목 ──
                y = drawCenteredText(cs, font, 18, "자동차 매매 계약서", pageWidth, y);
                y -= 30;

                // ── 계약 날짜 ──
                String dateStr = v.getPurchaseDate() != null
                        ? v.getPurchaseDate().format(DATE_KR_FMT)
                        : "____년 __월 __일";
                y = drawText(cs, font, 10, "계약일: " + dateStr, margin, y);
                y -= 25;

                // ── 구분선 ──
                y = drawLine(cs, margin, y, margin + contentWidth, y);
                y -= 20;

                // ── 당사자 정보 ──
                y = drawText(cs, font, 12, "【 당 사 자 】", margin, y);
                y -= 22;
                y = drawLabelValue(cs, font, 10, "양도인(매도인)", safe(v.getOwnerName()), margin, y, contentWidth);
                y -= 18;
                y = drawLabelValue(cs, font, 10, "주민등록번호", safe(v.getOwnerId()), margin, y, contentWidth);
                y -= 30;

                // ── 차량 정보 ──
                y = drawText(cs, font, 12, "【 매매 목적물(자동차) 】", margin, y);
                y -= 22;

                String[][] vehicleFields = {
                        {"자동차등록번호", safe(v.getVehicleNo())},
                        {"차명", safe(v.getModelName())},
                        {"차종(연식)", safe(v.getCarType()) + (v.getModelYear() != null ? "(" + v.getModelYear() + ")" : "")},
                        {"차대번호", safe(v.getVin())},
                        {"주행거리", v.getMileageKm() != null ? v.getMileageKm() + " km" : "-"},
                        {"색상", safe(v.getColor()).isEmpty() ? "-" : safe(v.getColor())},
                };

                for (String[] field : vehicleFields) {
                    y = drawLabelValue(cs, font, 10, field[0], field[1], margin, y, contentWidth);
                    y -= 16;
                }
                y -= 10;

                // ── 매매 금액 ──
                y = drawText(cs, font, 12, "【 매매 대금 】", margin, y);
                y -= 22;
                String priceStr = v.getPurchasePrice() != null && v.getPurchasePrice() > 0
                        ? PRICE_FMT.format(v.getPurchasePrice()) + " 원"
                        : "_____________ 원";
                y = drawLabelValue(cs, font, 11, "매매금액", priceStr, margin, y, contentWidth);
                y -= 30;

                // ── 구분선 ──
                y = drawLine(cs, margin, y, margin + contentWidth, y);
                y -= 20;

                // ── 계약 조건 ──
                y = drawText(cs, font, 12, "【 계약 조건 】", margin, y);
                y -= 20;

                String[] clauses = {
                        "제1조  양도인은 위 자동차를 현 상태대로 양수인에게 매도하고,",
                        "       양수인은 이를 매수한다.",
                        "제2조  양도인은 위 자동차에 대하여 소유권이전에 필요한 일체의",
                        "       서류를 양수인에게 교부한다.",
                        "제3조  양도인은 매매 목적물에 대한 저당권, 압류, 가압류 등",
                        "       권리의 하자가 없음을 보증한다.",
                        "제4조  본 계약에 명시되지 않은 사항은 민법 및 관련 법규에 따른다.",
                };

                for (String clause : clauses) {
                    y = drawText(cs, font, 9, clause, margin, y);
                    y -= 14;
                }
                y -= 25;

                // ── 서명란 ──
                y = drawLine(cs, margin, y, margin + contentWidth, y);
                y -= 25;

                y = drawText(cs, font, 10,
                        "위 계약을 증명하기 위하여 본 계약서를 작성하고 양 당사자가 서명 날인한다.",
                        margin, y);
                y -= 30;

                y = drawText(cs, font, 10, dateStr, margin + contentWidth / 2 - 50, y);
                y -= 30;

                y = drawLabelValue(cs, font, 10, "양도인(매도인)", safe(v.getOwnerName()) + "  (인)", margin, y, contentWidth);
                y -= 20;
                y = drawLabelValue(cs, font, 10, "주민등록번호", safe(v.getOwnerId()), margin, y, contentWidth);
                y -= 25;

                y = drawLabelValue(cs, font, 10, "양수인(매수인)", "____________________  (인)", margin, y, contentWidth);
                y -= 20;
                drawLabelValue(cs, font, 10, "사업자등록번호", "____________________", margin, y, contentWidth);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ─── PDF 텍스트 헬퍼 ───

    private float drawText(PDPageContentStream cs, PDType0Font font, float fontSize,
                           String text, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        return y;
    }

    private float drawCenteredText(PDPageContentStream cs, PDType0Font font, float fontSize,
                                   String text, float pageWidth, float y) throws IOException {
        float textWidth = font.getStringWidth(text) / 1000 * fontSize;
        float x = (pageWidth - textWidth) / 2;
        return drawText(cs, font, fontSize, text, x, y);
    }

    private float drawLabelValue(PDPageContentStream cs, PDType0Font font, float fontSize,
                                 String label, String value, float x, float y, float contentWidth) throws IOException {
        String labelText = label + " :  ";
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x + 20, y);
        cs.showText(labelText + value);
        cs.endText();
        return y;
    }

    private float drawLine(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.setLineWidth(0.5f);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
        return y1;
    }

    // ─── 매매계약서 엑셀 (fallback) ───

    private byte[] generateContractExcel(Vehicle v) throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);

        try (InputStream is = resource.getInputStream();
             HSSFWorkbook wb = new HSSFWorkbook(is)) {

            Sheet sheet = wb.getSheet("계약서");
            if (sheet == null) {
                sheet = wb.getSheetAt(1); // fallback: 두 번째 시트
            }

            // 날짜
            String dateStr = formatDate(v.getPurchaseDate());
            setCellValue(sheet, 5, 3, dateStr);
            setCellValue(sheet, 30, 4, dateStr);

            // 양도인
            setCellValue(sheet, 5, 8, safe(v.getOwnerName()));
            setCellValue(sheet, 31, 4, safe(v.getOwnerName()));

            // 양도인 주민등록번호
            setCellValue(sheet, 32, 4, safe(v.getOwnerId()));

            // 자동차등록번호
            setCellValue(sheet, 8, 3, safe(v.getVehicleNo()));

            // 매매금액
            if (v.getPurchasePrice() != null && v.getPurchasePrice() > 0) {
                setCellValue(sheet, 8, 7, v.getPurchasePrice());
            }

            // 차종(연식)
            String carTypeYear = safe(v.getCarType());
            if (v.getModelYear() != null) {
                carTypeYear += "(" + v.getModelYear() + ")";
            }
            setCellValue(sheet, 9, 3, carTypeYear);

            // 차명
            setCellValue(sheet, 10, 3, safe(v.getModelName()));

            // 차대번호
            setCellValue(sheet, 11, 3, safe(v.getVin()));

            // 주행거리
            if (v.getMileageKm() != null) {
                setCellValue(sheet, 12, 6, v.getMileageKm() + "km");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
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
