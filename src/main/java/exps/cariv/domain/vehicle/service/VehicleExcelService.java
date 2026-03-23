package exps.cariv.domain.vehicle.service;

import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.domain.vehicle.entity.VehicleStage;
import exps.cariv.domain.vehicle.repository.VehicleRepository;
import exps.cariv.domain.vehicle.repository.VehicleSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 차량 목록 엑셀 내보내기 서비스.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VehicleExcelService {

    private final VehicleRepository vehicleRepo;

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
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            int rowIdx = 1;
            for (Vehicle v : vehicles) {
                Row row = sheet.createRow(rowIdx);
                int col = 0;

                row.createCell(col++).setCellValue(rowIdx);  // No
                row.createCell(col++).setCellValue(stageLabel(v.getStage()));  // 상태
                row.createCell(col++).setCellValue(formatInstant(v.getCreatedAt()));  // 등록일
                row.createCell(col++).setCellValue(safe(v.getVehicleNo()));  // 차량번호
                row.createCell(col++).setCellValue(safe(v.getModelName()));  // 차량명
                row.createCell(col++).setCellValue(safe(v.getVin()));  // 차대번호
                setCellInt(row.createCell(col++), v.getModelYear());  // 연식
                row.createCell(col++).setCellValue(safe(v.getCarType()));  // 차종
                row.createCell(col++).setCellValue(safe(v.getVehicleUse()));  // 용도
                row.createCell(col++).setCellValue(safe(v.getOwnerName()));  // 소유자
                setCellLong(row.createCell(col++), v.getMileageKm());  // 주행거리
                setCellInt(row.createCell(col++), v.getDisplacement());  // 배기량
                row.createCell(col++).setCellValue(safe(v.getFuelType()));  // 연료
                row.createCell(col++).setCellValue(v.getTransmission() != null ? v.getTransmission().name() : "");  // 변속기
                row.createCell(col++).setCellValue(safe(v.getColor()));  // 색상
                row.createCell(col++).setCellValue(formatDate(v.getFirstRegistrationDate()));  // 최초등록일
                row.createCell(col++).setCellValue(safe(v.getShipperName()));  // 화주명
                setCellLong(row.createCell(col++), v.getPurchasePrice());  // 매입가
                row.createCell(col++).setCellValue(formatDate(v.getPurchaseDate()));  // 매입일
                row.createCell(col++).setCellValue(formatDate(v.getDeRegistrationDate()));  // 말소등록일

                rowIdx++;
            }

            // --- 열 너비 자동 조정 ---
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
                // 최소 너비 보정
                if (sheet.getColumnWidth(i) < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ─── 유틸 ───

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

    private void setCellInt(Cell cell, Integer value) {
        if (value != null) cell.setCellValue(value);
        else cell.setCellValue("");
    }

    private void setCellLong(Cell cell, Long value) {
        if (value != null) cell.setCellValue(value);
        else cell.setCellValue("");
    }
}
