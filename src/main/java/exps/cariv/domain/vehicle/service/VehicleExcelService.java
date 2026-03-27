package exps.cariv.domain.vehicle.service;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentType;
import exps.cariv.domain.document.repository.DocumentRepository;
import exps.cariv.domain.shipper.entity.IdCardDocument;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.repository.VehicleSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import exps.cariv.domain.vehicle.entity.OwnerType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 차량 목록 엑셀 내보내기 서비스.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleExcelService {

    private final VehicleRepository vehicleRepo;
    private final DocumentRepository documentRepo;

    private static final String[] HEADERS = {
            "No", "상태", "등록일", "차량번호", "차량명", "차대번호",
            "연식", "차종", "용도", "소유자", "주행거리(km)",
            "배기량(cc)", "연료", "변속기", "색상",
            "최초등록일", "화주명", "매입가",
            "매입일", "말소등록일"
    };

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 월별 차량 목록을 엑셀 바이트 배열로 생성.
     *
     * @param companyId 회사 ID
     * @param yearMonth 조회 대상 연월 (null이면 전체)
     * @return xlsx 바이트 배열
     */
    @Transactional(readOnly = true)
    public byte[] exportToExcel(Long companyId, YearMonth yearMonth) throws IOException {
        LocalDate from = null;
        LocalDate to = null;
        if (yearMonth != null) {
            from = yearMonth.atDay(1);
            to = yearMonth.atEndOfMonth();
        }

        Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                .and(VehicleSpecification.notDeleted())
                .and(VehicleSpecification.createdAfter(from))
                .and(VehicleSpecification.createdBefore(to));

        List<Vehicle> vehicles = vehicleRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("차량 현황");

            // --- 헤더 스타일 ---
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 11);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // --- 헤더 행 ---
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // --- 데이터 행 ---
            CellStyle centerStyle = wb.createCellStyle();
            centerStyle.setAlignment(HorizontalAlignment.CENTER);
            centerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            numberStyle.setAlignment(HorizontalAlignment.CENTER);
            numberStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            int rowIdx = 1;
            for (Vehicle v : vehicles) {
                Row row = sheet.createRow(rowIdx);
                int col = 0;

                createCenterCell(row, col++, String.valueOf(rowIdx), centerStyle);   // No
                createCenterCell(row, col++, stageLabel(v.getStage()), centerStyle);  // 상태
                createCenterCell(row, col++, formatInstant(v.getCreatedAt()), centerStyle);  // 등록일
                createCenterCell(row, col++, safe(v.getVehicleNo()), centerStyle);  // 차량번호
                createCenterCell(row, col++, safe(v.getModelName()), centerStyle);  // 차량명
                createCenterCell(row, col++, safe(v.getVin()), centerStyle);  // 차대번호
                createCenterCell(row, col++, v.getModelYear() != null ? String.valueOf(v.getModelYear()) : "", centerStyle);  // 연식
                createCenterCell(row, col++, safe(v.getCarType()), centerStyle);  // 차종
                createCenterCell(row, col++, safe(v.getVehicleUse()), centerStyle);  // 용도
                createCenterCell(row, col++, safe(v.getOwnerName()), centerStyle);  // 소유자
                createCenterCell(row, col++, v.getMileageKm() != null ? String.valueOf(v.getMileageKm()) : "", centerStyle);  // 주행거리
                createCenterCell(row, col++, v.getDisplacement() != null ? String.valueOf(v.getDisplacement()) : "", centerStyle);  // 배기량
                createCenterCell(row, col++, safe(v.getFuelType()), centerStyle);  // 연료
                createCenterCell(row, col++, v.getTransmission() != null ? v.getTransmission().name() : "", centerStyle);  // 변속기
                createCenterCell(row, col++, safe(v.getColor()), centerStyle);  // 색상
                createCenterCell(row, col++, formatDate(v.getFirstRegistrationDate()), centerStyle);  // 최초등록일
                createCenterCell(row, col++, safe(v.getShipperName()), centerStyle);  // 화주명
                Cell priceCell1 = row.createCell(col++);
                if (v.getPurchasePrice() != null) priceCell1.setCellValue(v.getPurchasePrice());
                else priceCell1.setCellValue("");
                priceCell1.setCellStyle(numberStyle);  // 매입가
                createCenterCell(row, col++, formatDate(v.getPurchaseDate()), centerStyle);  // 매입일
                createCenterCell(row, col++, formatDate(v.getDeRegistrationDate()), centerStyle);  // 말소등록일

                rowIdx++;
            }

            // --- 열 너비 자동 조정 ---
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            // --- A4 인쇄 설정 ---
            setupA4Print(sheet, HEADERS.length);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 매입일 기준 월별 차량 목록을 엑셀 바이트 배열로 생성.
     * 환급 관련서류 ZIP의 "차량현황" 시트 생성에 사용합니다.
     */
    @Transactional(readOnly = true)
    public byte[] exportToExcelByPurchaseMonth(Long companyId, YearMonth yearMonth) throws IOException {
        LocalDate from = null;
        LocalDate to = null;
        if (yearMonth != null) {
            from = yearMonth.atDay(1);
            to = yearMonth.atEndOfMonth();
        }

        Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                .and(VehicleSpecification.notDeleted())
                .and(VehicleSpecification.purchaseDateAfter(from))
                .and(VehicleSpecification.purchaseDateBefore(to));

        List<Vehicle> vehicles = vehicleRepo.findAll(spec, Sort.by(Sort.Direction.ASC, "purchaseDate"));

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("차량 현황");

            CellStyle headerStyle = createHeaderStyle(wb);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            CellStyle centerStyle2 = wb.createCellStyle();
            centerStyle2.setAlignment(HorizontalAlignment.CENTER);
            centerStyle2.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle numberStyle2 = wb.createCellStyle();
            numberStyle2.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            numberStyle2.setAlignment(HorizontalAlignment.CENTER);
            numberStyle2.setVerticalAlignment(VerticalAlignment.CENTER);

            int rowIdx = 1;
            for (Vehicle v : vehicles) {
                Row row = sheet.createRow(rowIdx);
                int col = 0;

                createCenterCell(row, col++, String.valueOf(rowIdx), centerStyle2);
                createCenterCell(row, col++, stageLabel(v.getStage()), centerStyle2);
                createCenterCell(row, col++, formatInstant(v.getCreatedAt()), centerStyle2);
                createCenterCell(row, col++, safe(v.getVehicleNo()), centerStyle2);
                createCenterCell(row, col++, safe(v.getModelName()), centerStyle2);
                createCenterCell(row, col++, safe(v.getVin()), centerStyle2);
                createCenterCell(row, col++, v.getModelYear() != null ? String.valueOf(v.getModelYear()) : "", centerStyle2);
                createCenterCell(row, col++, safe(v.getCarType()), centerStyle2);
                createCenterCell(row, col++, safe(v.getVehicleUse()), centerStyle2);
                createCenterCell(row, col++, safe(v.getOwnerName()), centerStyle2);
                createCenterCell(row, col++, v.getMileageKm() != null ? String.valueOf(v.getMileageKm()) : "", centerStyle2);
                createCenterCell(row, col++, v.getDisplacement() != null ? String.valueOf(v.getDisplacement()) : "", centerStyle2);
                createCenterCell(row, col++, safe(v.getFuelType()), centerStyle2);
                createCenterCell(row, col++, v.getTransmission() != null ? v.getTransmission().name() : "", centerStyle2);
                createCenterCell(row, col++, safe(v.getColor()), centerStyle2);
                createCenterCell(row, col++, formatDate(v.getFirstRegistrationDate()), centerStyle2);
                createCenterCell(row, col++, safe(v.getShipperName()), centerStyle2);
                Cell priceCell2 = row.createCell(col++);
                if (v.getPurchasePrice() != null) priceCell2.setCellValue(v.getPurchasePrice());
                else priceCell2.setCellValue("");
                priceCell2.setCellStyle(numberStyle2);
                createCenterCell(row, col++, formatDate(v.getPurchaseDate()), centerStyle2);
                createCenterCell(row, col++, formatDate(v.getDeRegistrationDate()), centerStyle2);

                rowIdx++;
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            setupA4Print(sheet, HEADERS.length);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ───────────────────────────────────────────────
    // 환급용 엑셀 내보내기 (개인계/법인계 시트 분리)
    // ───────────────────────────────────────────────

    private static final String[] REFUND_HEADERS = {
            "No", "매입일", "차량번호", "차량명", "차대번호",
            "이름", "주민번호", "주소",
            "매입금액", "수량"
    };

    /**
     * 환급용 엑셀: 매입일 기준 월별, 미신청/신청 구분.
     * 개인계(INDIVIDUAL, DEALER_INDIVIDUAL)와 법인계(DEALER_CORPORATE, CORPORATE_OTHER)를
     * 별도 시트로 분리하여 생성합니다.
     */
    /**
     * 환급 엑셀: 여러 월 + 개인/그밖의사업자 구분.
     * @param refundType "individual" = 개인(INDIVIDUAL + DEALER_INDIVIDUAL), "others" = 그밖의 사업자
     */
    @Transactional
    public byte[] exportRefundExcel(Long companyId, List<YearMonth> yearMonths, String refundType) throws IOException {
        // 여러 월에 걸쳐 차량 조회
        List<Vehicle> allVehicles = new java.util.ArrayList<>();
        for (YearMonth ym : yearMonths) {
            LocalDate from = ym.atDay(1);
            LocalDate to = ym.atEndOfMonth();
            Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                    .and(VehicleSpecification.notDeleted())
                    .and(VehicleSpecification.purchaseDateAfter(from))
                    .and(VehicleSpecification.purchaseDateBefore(to));
            allVehicles.addAll(vehicleRepo.findAll(spec, Sort.by(Sort.Direction.ASC, "purchaseDate")));
        }

        // 소유자 유형에 따라 필터
        boolean isIndividual = "individual".equalsIgnoreCase(refundType);
        List<Vehicle> filtered;
        if (isIndividual) {
            filtered = allVehicles.stream()
                    .filter(v -> v.getOwnerType() == OwnerType.INDIVIDUAL
                              || v.getOwnerType() == OwnerType.DEALER_INDIVIDUAL)
                    .collect(Collectors.toList());
        } else {
            filtered = allVehicles.stream()
                    .filter(v -> v.getOwnerType() == OwnerType.DEALER_CORPORATE
                              || v.getOwnerType() == OwnerType.CORPORATE_OTHER)
                    .collect(Collectors.toList());
        }

        // 소유자 주민번호/주소 조회: vehicleId → IdCardDocument 필드
        Map<Long, String> ownerIdNumberMap = new HashMap<>();
        Map<Long, String> ownerAddressMap = new HashMap<>();
        buildOwnerIdCardMaps(companyId, filtered, ownerIdNumberMap, ownerAddressMap);

        String sheetName = isIndividual ? "개인" : "그밖의 사업자";

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            createRefundSheet(wb, sheetName, filtered, headerStyle, numberStyle, ownerIdNumberMap, ownerAddressMap);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 환급 엑셀 미리보기: 엑셀과 동일한 데이터를 JSON으로 반환.
     */
    @Transactional(readOnly = true)
    public java.util.List<exps.cariv.domain.vehicle.dto.response.RefundExcelPreviewRow> previewRefundExcel(
            Long companyId, List<YearMonth> yearMonths, String refundType) {

        List<Vehicle> allVehicles = new java.util.ArrayList<>();
        for (YearMonth ym : yearMonths) {
            LocalDate from = ym.atDay(1);
            LocalDate to = ym.atEndOfMonth();
            Specification<Vehicle> spec = VehicleSpecification.companyIs(companyId)
                    .and(VehicleSpecification.notDeleted())
                    .and(VehicleSpecification.purchaseDateAfter(from))
                    .and(VehicleSpecification.purchaseDateBefore(to));
            allVehicles.addAll(vehicleRepo.findAll(spec, Sort.by(Sort.Direction.ASC, "purchaseDate")));
        }

        boolean isIndividual = "individual".equalsIgnoreCase(refundType);
        List<Vehicle> filtered;
        if (isIndividual) {
            filtered = allVehicles.stream()
                    .filter(v -> v.getOwnerType() == OwnerType.INDIVIDUAL
                              || v.getOwnerType() == OwnerType.DEALER_INDIVIDUAL)
                    .collect(Collectors.toList());
        } else {
            filtered = allVehicles.stream()
                    .filter(v -> v.getOwnerType() == OwnerType.DEALER_CORPORATE
                              || v.getOwnerType() == OwnerType.CORPORATE_OTHER)
                    .collect(Collectors.toList());
        }

        Map<Long, String> ownerIdNumberMap = new HashMap<>();
        Map<Long, String> ownerAddressMap = new HashMap<>();
        buildOwnerIdCardMaps(companyId, filtered, ownerIdNumberMap, ownerAddressMap);

        java.util.List<exps.cariv.domain.vehicle.dto.response.RefundExcelPreviewRow> rows = new java.util.ArrayList<>();
        int idx = 1;
        for (Vehicle v : filtered) {
            rows.add(new exps.cariv.domain.vehicle.dto.response.RefundExcelPreviewRow(
                    idx++,
                    formatDate(v.getPurchaseDate()),
                    safe(v.getVehicleNo()),
                    safe(v.getModelName()),
                    safe(v.getVin()),
                    safe(v.getOwnerName()),
                    safe(ownerIdNumberMap.getOrDefault(v.getId(), "")),
                    safe(ownerAddressMap.getOrDefault(v.getId(), "")),
                    v.getPurchasePrice() != null ? v.getPurchasePrice() : 0L,
                    "1"
            ));
        }
        return rows;
    }

    /** 하위호환: 단일 월 + appliedOnly 방식 */
    @Transactional
    public byte[] exportRefundExcel(Long companyId, YearMonth yearMonth, boolean appliedOnly) throws IOException {
        String refundType = appliedOnly ? "others" : "individual";
        return exportRefundExcel(companyId, List.of(yearMonth), refundType);
    }

    private void createRefundSheet(XSSFWorkbook wb, String sheetName, List<Vehicle> vehicles,
                                    CellStyle headerStyle, CellStyle numberStyle,
                                    Map<Long, String> ownerIdNumberMap,
                                    Map<Long, String> ownerAddressMap) {
        Sheet sheet = wb.createSheet(sheetName);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < REFUND_HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(REFUND_HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }

        CellStyle cs = wb.createCellStyle();
        cs.setAlignment(HorizontalAlignment.CENTER);
        cs.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle ns = wb.createCellStyle();
        ns.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
        ns.setAlignment(HorizontalAlignment.CENTER);
        ns.setVerticalAlignment(VerticalAlignment.CENTER);

        int rowIdx = 1;
        for (Vehicle v : vehicles) {
            Row row = sheet.createRow(rowIdx);
            int col = 0;

            createCenterCell(row, col++, String.valueOf(rowIdx), cs);                 // No
            createCenterCell(row, col++, formatDate(v.getPurchaseDate()), cs);         // 매입일
            createCenterCell(row, col++, safe(v.getVehicleNo()), cs);                 // 차량번호
            createCenterCell(row, col++, safe(v.getModelName()), cs);                 // 차량명
            createCenterCell(row, col++, safe(v.getVin()), cs);                       // 차대번호
            createCenterCell(row, col++, safe(v.getOwnerName()), cs);                 // 소유자
            createCenterCell(row, col++, safe(ownerIdNumberMap.getOrDefault(v.getId(), "")), cs); // 소유자 주민번호
            createCenterCell(row, col++, safe(ownerAddressMap.getOrDefault(v.getId(), "")), cs);  // 공급자(소유자) 주소
            Cell priceCell = row.createCell(col++);
            if (v.getPurchasePrice() != null) priceCell.setCellValue(v.getPurchasePrice());
            else priceCell.setCellValue(0);
            priceCell.setCellStyle(ns);                                                           // 매입금액
            createCenterCell(row, col++, "1", cs);                                                // 수량

            rowIdx++;
        }

        for (int i = 0; i < REFUND_HEADERS.length; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) < 3000) sheet.setColumnWidth(i, 3000);
        }

        setupA4Print(sheet, REFUND_HEADERS.length);
    }

    /**
     * 차량 ID → 소유자 주민번호/주소 맵 생성.
     * 차량에 연결된 ID_CARD(소유자 신분증) 문서에서 idNumber, idAddress를 가져온다.
     */
    private void buildOwnerIdCardMaps(Long companyId, List<Vehicle> vehicles,
                                       Map<Long, String> idNumberMap, Map<Long, String> addressMap) {
        for (Vehicle v : vehicles) {
            try {
                Optional<Document> idCardDoc = documentRepo
                        .findTopByCompanyIdAndRefTypeAndRefIdAndTypeOrderByUploadedAtDescIdDesc(
                                companyId, DocumentRefType.VEHICLE, v.getId(), DocumentType.ID_CARD);
                if (idCardDoc.isPresent() && idCardDoc.get() instanceof IdCardDocument idc) {
                    String idNum = idc.getIdNumber();
                    if (idNum != null && !idNum.isBlank()) {
                        idNumberMap.put(v.getId(), idNum);
                    }
                    String addr = idc.getIdAddress();
                    if (addr != null && !addr.isBlank()) {
                        addressMap.put(v.getId(), addr);
                    }
                }
            } catch (Exception e) {
                log.debug("소유자 신분증 조회 실패 vehicleId={}", v.getId(), e);
            }
        }
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    // ─── 유틸 ───

    /** A4 가로 인쇄 설정 — 모든 열이 한 페이지에 들어가도록 */
    private void setupA4Print(Sheet sheet, int colCount) {
        PrintSetup ps = sheet.getPrintSetup();
        ps.setPaperSize(PrintSetup.A4_PAPERSIZE);
        ps.setLandscape(true);                     // 가로
        ps.setFitWidth((short) 1);                 // 너비 1페이지에 맞춤
        ps.setFitHeight((short) 0);                // 높이는 자동
        sheet.setFitToPage(true);

        // 여백 (인치) — 좁은 여백
        sheet.setMargin(Sheet.LeftMargin, 0.4);
        sheet.setMargin(Sheet.RightMargin, 0.4);
        sheet.setMargin(Sheet.TopMargin, 0.5);
        sheet.setMargin(Sheet.BottomMargin, 0.5);

        // 헤더 행 반복 인쇄
        sheet.setRepeatingRows(new CellRangeAddress(0, 0, 0, colCount - 1));
    }

    private String stageLabel(VehicleStage stage) {
        if (stage == null) return "";
        return switch (stage) {
            case BEFORE_DEREGISTRATION -> "말소 전";
            case BEFORE_REPORT -> "신고 전";
            case BEFORE_CERTIFICATE -> "증권 전";
            case COMPLETED -> "완료";
        };
    }

    private String formatInstant(java.time.Instant instant) {
        if (instant == null) return "";
        return instant.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().format(DATE_FMT);
    }

    private String formatDate(LocalDate date) {
        if (date == null) return "";
        return date.format(DATE_FMT);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private void createCenterCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    private void setCellInt(Cell cell, Integer value) {
        if (value != null) cell.setCellValue(value);
        else cell.setCellValue("");
    }

    private void setCellLong(Cell cell, Long value) {
        if (value != null) cell.setCellValue(value);
        else cell.setCellValue("");
    }
}
