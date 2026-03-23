package exps.cariv.domain.customs.service;

import exps.cariv.domain.customs.entity.ContainerInfo;
import exps.cariv.domain.customs.entity.CustomsRequestVehicle;
import exps.cariv.domain.customs.entity.CustomsRequest;
import exps.cariv.domain.customs.entity.ShippingMethod;
import exps.cariv.domain.vehicle.entity.Vehicle;
import exps.cariv.global.parser.KoreanEnglishConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 신고필증 Commercial Invoice / Packing List XLSX 생성기.
 * <p>선적 방식에 따라 CI&PL 템플릿(RORO/CONTAINER)을 선택하고 데이터 영역을 채운다.</p>
 */
@Component
@Slf4j
public class CustomsInvoiceXlsxGenerator {

    public record ShipperInfo(String name, String address, String phone) {
    }

    private static final String TEMPLATE_RORO = "templates/CI&PL_RoRonew.xlsx";
    private static final String TEMPLATE_CONTAINER = "templates/CI&PL_Containernew.xlsx";

    // 헤더/요약 영역
    private static final String INVOICE_NO_CELL = "E3";
    private static final String INVOICE_DATE_CELL = "O3";
    private static final String SHIPPER_NAME_CELL = "C5";
    private static final String SHIPPER_ADDRESS_CELL = "C6";
    private static final String SHIPPER_PHONE_CELL = "C9";
    private static final String CONSIGNEE_NAME_CELL = "K5";
    private static final String CONSIGNEE_ADDRESS_CELL = "K6";
    private static final String CONSIGNEE_PHONE_CELL = "K9";
    private static final String EXPORT_COUNTRY_CELL = "S5";
    private static final String DEST_COUNTRY_CELL = "S9";
    private static final String DELIVERY_TERMS_CELL = "S11";
    private static final String EXPORT_PORT_CELL = "S7";

    // Container 전용
    private static final String CONTAINER_NO_CELL = "E13";
    private static final String SEAL_NO_CELL = "N13";
    private static final String SHORING_YARD_CELL = "E14";
    private static final String CY_GATEIN_CELL = "N14";
    private static final String CONTAINER_REMARK_CELL = "S13";

    // RORO 전용
    private static final String WAREHOUSE_LOCATION_CELL = "A13";
    private static final String RORO_REMARK_CELL = "M13";

    // 품목 테이블
    private static final int TABLE_START_ROW = 16; // excel row 17
    private static final int TABLE_TEMPLATE_LAST_ROW = 25; // excel row 26
    private static final int COL_NO = 0;          // A
    private static final int COL_ITEM_TYPE = 1;   // B
    private static final int COL_DESCRIPTION = 3; // D
    private static final int COL_YEAR = 6;        // G
    private static final int COL_VIN = 7;         // H
    private static final int COL_FUEL = 11;       // L
    private static final int COL_CC = 13;         // N
    private static final int COL_HS_CODE = 15;    // P
    private static final int COL_WEIGHT_NET = 17; // R
    private static final int COL_WEIGHT_GROSS = 18;// S
    private static final int COL_CBM = 19;        // T
    private static final int COL_UNIT_PRICE = 21; // V
    private static final int COL_QTY = 23;        // X
    private static final int COL_AMOUNT = 24;     // Y
    private static final int TOTAL_ROW = 26;      // excel row 27

    // Freight Charge / Insurance Premium 행 (new 템플릿)
    private static final int FREIGHT_CHARGE_ROW = 24;    // excel row 25
    private static final int INSURANCE_PREMIUM_ROW = 25;  // excel row 26
    private static final int COL_FEE_AMOUNT = 24;         // Y열

    private static final String DEFAULT_ITEM_TYPE = "Usedcar";
    private static final String DEFAULT_HS6_FALLBACK = "870323";
    private static final String DEFAULT_HS10_SUFFIX = "9090";
    private static final String FIXED_EXPORT_COUNTRY = "South Korea";
    private static final String DEFAULT_NA = "N/A";

    // 사인 이미지 영역(F27:L29)
    private static final int SIGN_COL1 = 5;
    private static final int SIGN_ROW1 = 26;
    private static final int SIGN_COL2 = 11;
    private static final int SIGN_ROW2 = 28;
    private static final int SIGN_DX1 = 95250;
    private static final int SIGN_DY1 = 52070;
    private static final int SIGN_DX2 = 342900;
    private static final int SIGN_DY2 = 166370;

    /**
     * 단일 차량 Invoice 생성 (RORO 또는 단독).
     */
    public byte[] generate(Vehicle vehicle, CustomsRequestVehicle crv, ContainerInfo ci) {
        ShippingMethod method = ci != null ? ShippingMethod.CONTAINER : ShippingMethod.RORO;
        ShipperInfo shipperInfo = new ShipperInfo(vehicle.getShipperName(), null, null);
        return generateInternal(
                List.of(vehicle),
                List.of(crv),
                ci,
                method,
                shipperInfo,
                null,
                Map.of(),
                Map.of(),
                null
        );
    }

    /**
     * 단일 차량 Invoice 생성 (선적 방식 명시 버전).
     */
    public byte[] generate(Vehicle vehicle,
                           CustomsRequestVehicle crv,
                           ContainerInfo ci,
                           ShippingMethod shippingMethod) {
        ShipperInfo shipperInfo = new ShipperInfo(vehicle.getShipperName(), null, null);
        return generateInternal(
                List.of(vehicle),
                List.of(crv),
                ci,
                shippingMethod,
                shipperInfo,
                null,
                Map.of(),
                Map.of(),
                null
        );
    }

    /**
     * 단일 차량 Invoice 생성 (선적 방식 + CBM 명시 버전).
     */
    public byte[] generate(Vehicle vehicle,
                           CustomsRequestVehicle crv,
                           ContainerInfo ci,
                           ShippingMethod shippingMethod,
                           Map<Long, BigDecimal> cbmByVehicleId) {
        ShipperInfo shipperInfo = new ShipperInfo(vehicle.getShipperName(), null, null);
        return generateInternal(
                List.of(vehicle),
                List.of(crv),
                ci,
                shippingMethod,
                shipperInfo,
                null,
                cbmByVehicleId,
                Map.of(),
                null
        );
    }

    /**
     * 단일 차량 Invoice 생성 (선적 방식 + 화주정보 + 사인 + CBM/중량).
     */
    public byte[] generate(Vehicle vehicle,
                           CustomsRequestVehicle crv,
                           ContainerInfo ci,
                           ShippingMethod shippingMethod,
                           ShipperInfo shipperInfo,
                           byte[] shipperSignaturePng,
                           Map<Long, BigDecimal> cbmByVehicleId,
                           Map<Long, BigDecimal> weightByVehicleId) {
        return generate(
                vehicle,
                crv,
                ci,
                shippingMethod,
                shipperInfo,
                shipperSignaturePng,
                cbmByVehicleId,
                weightByVehicleId,
                null
        );
    }

    /**
     * 단일 차량 Invoice 생성 (선적 방식 + 화주정보 + 사인 + CBM/중량 + RORO 창고 위치).
     */
    public byte[] generate(Vehicle vehicle,
                           CustomsRequestVehicle crv,
                           ContainerInfo ci,
                           ShippingMethod shippingMethod,
                           ShipperInfo shipperInfo,
                           byte[] shipperSignaturePng,
                           Map<Long, BigDecimal> cbmByVehicleId,
                           Map<Long, BigDecimal> weightByVehicleId,
                           String warehouseLocation) {
        return generateInternal(
                List.of(vehicle),
                List.of(crv),
                ci,
                shippingMethod,
                shipperInfo,
                shipperSignaturePng,
                cbmByVehicleId,
                weightByVehicleId,
                warehouseLocation
        );
    }

    /**
     * 다건 차량 Invoice 생성 — 여러 차량을 하나의 XLSX 에 나열.
     */
    public byte[] generateMulti(List<Vehicle> vehicles,
                                List<CustomsRequestVehicle> crvs,
                                ContainerInfo ci) {
        ShippingMethod method = ci != null ? ShippingMethod.CONTAINER : ShippingMethod.RORO;
        String shipperName = vehicles.isEmpty() ? "" : vehicles.get(0).getShipperName();
        return generateInternal(
                vehicles,
                crvs,
                ci,
                method,
                new ShipperInfo(shipperName, null, null),
                null,
                Map.of(),
                Map.of(),
                null
        );
    }

    /**
     * 다건 차량 Invoice 생성 (선적 방식 명시 버전).
     */
    public byte[] generateMulti(List<Vehicle> vehicles,
                                List<CustomsRequestVehicle> crvs,
                                ContainerInfo ci,
                                ShippingMethod shippingMethod) {
        String shipperName = vehicles.isEmpty() ? "" : vehicles.get(0).getShipperName();
        return generateInternal(
                vehicles,
                crvs,
                ci,
                shippingMethod,
                new ShipperInfo(shipperName, null, null),
                null,
                Map.of(),
                Map.of(),
                null
        );
    }

    /**
     * 다건 차량 Invoice 생성 (선적 방식 + CBM 명시 버전).
     */
    public byte[] generateMulti(List<Vehicle> vehicles,
                                List<CustomsRequestVehicle> crvs,
                                ContainerInfo ci,
                                ShippingMethod shippingMethod,
                                Map<Long, BigDecimal> cbmByVehicleId) {
        String shipperName = vehicles.isEmpty() ? "" : vehicles.get(0).getShipperName();
        return generateInternal(
                vehicles,
                crvs,
                ci,
                shippingMethod,
                new ShipperInfo(shipperName, null, null),
                null,
                cbmByVehicleId,
                Map.of(),
                null
        );
    }

    /**
     * 다건 차량 Invoice 생성 (선적 방식 + 화주정보 + 사인 + CBM/중량).
     */
    public byte[] generateMulti(List<Vehicle> vehicles,
                                List<CustomsRequestVehicle> crvs,
                                ContainerInfo ci,
                                ShippingMethod shippingMethod,
                                ShipperInfo shipperInfo,
                                byte[] shipperSignaturePng,
                                Map<Long, BigDecimal> cbmByVehicleId,
                                Map<Long, BigDecimal> weightByVehicleId) {
        return generateMulti(
                vehicles,
                crvs,
                ci,
                shippingMethod,
                shipperInfo,
                shipperSignaturePng,
                cbmByVehicleId,
                weightByVehicleId,
                null
        );
    }

    /**
     * 다건 차량 Invoice 생성 (선적 방식 + 화주정보 + 사인 + CBM/중량 + RORO 창고 위치).
     */
    public byte[] generateMulti(List<Vehicle> vehicles,
                                List<CustomsRequestVehicle> crvs,
                                ContainerInfo ci,
                                ShippingMethod shippingMethod,
                                ShipperInfo shipperInfo,
                                byte[] shipperSignaturePng,
                                Map<Long, BigDecimal> cbmByVehicleId,
                                Map<Long, BigDecimal> weightByVehicleId,
                                String warehouseLocation) {
        return generateInternal(
                vehicles,
                crvs,
                ci,
                shippingMethod,
                shipperInfo,
                shipperSignaturePng,
                cbmByVehicleId,
                weightByVehicleId,
                warehouseLocation
        );
    }

    private byte[] generateInternal(List<Vehicle> vehicles,
                                    List<CustomsRequestVehicle> crvs,
                                    ContainerInfo ci,
                                    ShippingMethod shippingMethod,
                                    ShipperInfo shipperInfo,
                                    byte[] shipperSignaturePng,
                                    Map<Long, BigDecimal> cbmByVehicleId,
                                    Map<Long, BigDecimal> weightByVehicleId,
                                    String warehouseLocation) {
        String template = shippingMethod == ShippingMethod.CONTAINER
                ? TEMPLATE_CONTAINER
                : TEMPLATE_RORO;

        try (InputStream is = new ClassPathResource(template).getInputStream();
             XSSFWorkbook wb = new XSSFWorkbook(is);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.getSheetAt(0);

            // RORO 템플릿에 포함된 빈 시트는 제거해 PDF 변환 시 불필요한 페이지를 방지합니다.
            while (wb.getNumberOfSheets() > 1) {
                wb.removeSheetAt(wb.getNumberOfSheets() - 1);
            }

            populateHeader(sheet, vehicles, crvs, ci, shipperInfo, shippingMethod, warehouseLocation);
            clearTemplateRows(sheet);
            fillVehicleRows(sheet, vehicles, crvs, cbmByVehicleId, weightByVehicleId);
            fillFreightAndInsurance(sheet, crvs);
            applySignatureImage(sheet, wb, shipperSignaturePng);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate customs CI/PL xlsx", e);
        }
    }

    private void populateHeader(Sheet sheet,
                                List<Vehicle> vehicles,
                                List<CustomsRequestVehicle> crvs,
                                ContainerInfo ci,
                                ShipperInfo shipperInfo,
                                ShippingMethod shippingMethod,
                                String warehouseLocation) {
        Vehicle firstVehicle = vehicles.isEmpty() ? null : vehicles.get(0);
        CustomsRequestVehicle firstCrv = crvs.isEmpty() ? null : crvs.get(0);
        String shipperName = KoreanEnglishConverter.toEnglish(shipperInfo == null ? "" : safe(shipperInfo.name()));
        String shipperAddress = KoreanEnglishConverter.toEnglish(shipperInfo == null ? "" : safe(shipperInfo.address()));
        String shipperPhone = shipperInfo == null ? "" : safe(shipperInfo.phone());
        String consigneeName = KoreanEnglishConverter.toEnglish(ci == null ? null : safe(ci.getConsignee()));
        String exportPort = KoreanEnglishConverter.toEnglish(ci == null ? null : safe(ci.getExportPort()));
        String destination = KoreanEnglishConverter.toEnglish(ci != null ? safe(ci.getDestinationCountry()) : "");
        String warehouseOrVessel = KoreanEnglishConverter.toEnglish(
                ci == null ? null : safe(firstNonBlank(ci.getWarehouseLocation(), ci.getVesselName()))
        );
        String entryPort = KoreanEnglishConverter.toEnglish(ci == null ? null : safe(ci.getEntryPort()));

        // 샘플값(#000001)을 남기지 않도록 기본값도 항상 덮어씁니다.
        setString(sheet, INVOICE_NO_CELL, valueOrNa(resolveInvoiceNo(firstVehicle, firstCrv)));
        setNumeric(sheet, INVOICE_DATE_CELL, DateUtil.getExcelDate(LocalDate.now()));

        setString(sheet, SHIPPER_NAME_CELL, valueOrNa(shipperName));
        setString(sheet, SHIPPER_ADDRESS_CELL, valueOrNa(shipperAddress));
        setString(sheet, SHIPPER_PHONE_CELL, valueOrNa(shipperPhone));

        setString(sheet, CONSIGNEE_NAME_CELL, valueOrNa(consigneeName));
        // 현재 요청 스키마에 consignee address/tel 필드가 없어 N/A로 고정합니다.
        setString(sheet, CONSIGNEE_ADDRESS_CELL, DEFAULT_NA);
        setString(sheet, CONSIGNEE_PHONE_CELL, DEFAULT_NA);

        setString(sheet, EXPORT_COUNTRY_CELL, FIXED_EXPORT_COUNTRY);
        setString(sheet, EXPORT_PORT_CELL, valueOrNa(exportPort));

        setString(sheet, DEST_COUNTRY_CELL, valueOrNa(destination));

        String deliveryTerms = "";
        if (firstCrv != null && firstCrv.getTradeCondition() != null) {
            deliveryTerms = firstCrv.getTradeCondition().name();
            if (!isBlank(exportPort)) {
                deliveryTerms = deliveryTerms + " " + exportPort;
            }
        }
        setString(sheet, DELIVERY_TERMS_CELL, valueOrNa(deliveryTerms));

        if (shippingMethod == ShippingMethod.CONTAINER) {
            setString(sheet, CONTAINER_NO_CELL, valueOrNa(ci == null ? null : safe(ci.getContainerNo())));
            setString(sheet, SEAL_NO_CELL, valueOrNa(ci == null ? null : safe(ci.getSealNo())));
            setString(sheet, SHORING_YARD_CELL, valueOrNa(warehouseOrVessel));
            setString(sheet, CY_GATEIN_CELL, valueOrNa(entryPort));
            setString(sheet, CONTAINER_REMARK_CELL, DEFAULT_NA);
        } else {
            setString(sheet, WAREHOUSE_LOCATION_CELL, valueOrNa(KoreanEnglishConverter.toEnglish(warehouseLocation)));
            setString(sheet, RORO_REMARK_CELL, DEFAULT_NA);
        }
    }

    private void clearTemplateRows(Sheet sheet) {
        for (int rowIdx = TABLE_START_ROW; rowIdx <= TABLE_TEMPLATE_LAST_ROW; rowIdx++) {
            clearCell(sheet, rowIdx, COL_NO);
            clearCell(sheet, rowIdx, COL_ITEM_TYPE);
            clearCell(sheet, rowIdx, COL_DESCRIPTION);
            clearCell(sheet, rowIdx, COL_YEAR);
            clearCell(sheet, rowIdx, COL_VIN);
            clearCell(sheet, rowIdx, COL_FUEL);
            clearCell(sheet, rowIdx, COL_CC);
            clearCell(sheet, rowIdx, COL_HS_CODE);
            clearCell(sheet, rowIdx, COL_WEIGHT_NET);
            clearCell(sheet, rowIdx, COL_WEIGHT_GROSS);
            clearCell(sheet, rowIdx, COL_CBM);
            clearCell(sheet, rowIdx, COL_UNIT_PRICE);
            clearCell(sheet, rowIdx, COL_QTY);
            clearCell(sheet, rowIdx, COL_AMOUNT);
        }
    }

    private void fillVehicleRows(Sheet sheet,
                                 List<Vehicle> vehicles,
                                 List<CustomsRequestVehicle> crvs,
                                 Map<Long, BigDecimal> cbmByVehicleId,
                                 Map<Long, BigDecimal> weightByVehicleId) {
        int rowsToWrite = Math.min(vehicles.size(), crvs.size());
        if (rowsToWrite > (TABLE_TEMPLATE_LAST_ROW - TABLE_START_ROW + 1)) {
            log.warn("[CustomsInvoiceXlsxGenerator] CI/PL rows exceed template capacity. rows={} capacity={}",
                    rowsToWrite, (TABLE_TEMPLATE_LAST_ROW - TABLE_START_ROW + 1));
        }

        int writable = Math.min(rowsToWrite, TABLE_TEMPLATE_LAST_ROW - TABLE_START_ROW + 1);
        BigDecimal totalNetWeight = BigDecimal.ZERO;
        BigDecimal totalGrossWeight = BigDecimal.ZERO;
        BigDecimal totalCbm = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalQty = 0;
        boolean hasWeight = false;
        boolean hasCbm = false;
        boolean hasAmount = false;

        for (int i = 0; i < writable; i++) {
            int rowIdx = TABLE_START_ROW + i;
            Vehicle v = vehicles.get(i);
            CustomsRequestVehicle crv = crvs.get(i);

            setNumericAt(sheet, rowIdx, COL_NO, i + 1);
            setStringAt(sheet, rowIdx, COL_ITEM_TYPE, DEFAULT_ITEM_TYPE);
            setStringAt(sheet, rowIdx, COL_DESCRIPTION, valueOrNa(KoreanEnglishConverter.toEnglishVehicleName(v.getModelName())));
            setNumericOrNa(sheet, rowIdx, COL_YEAR, v.getModelYear());
            setStringAt(sheet, rowIdx, COL_VIN, valueOrNa(v.getVin()));
            setStringAt(sheet, rowIdx, COL_FUEL, valueOrNa(KoreanEnglishConverter.toEnglishFuel(v.getFuelType())));
            setNumericOrNa(sheet, rowIdx, COL_CC, v.getDisplacement());
            setStringAt(sheet, rowIdx, COL_HS_CODE, resolveHs10OrNa(v));

            BigDecimal weight = weightByVehicleId.get(v.getId());
            if (weight != null) {
                setNumericAt(sheet, rowIdx, COL_WEIGHT_NET, weight.doubleValue());
                setNumericAt(sheet, rowIdx, COL_WEIGHT_GROSS, weight.doubleValue());
                totalNetWeight = totalNetWeight.add(weight);
                totalGrossWeight = totalGrossWeight.add(weight);
                hasWeight = true;
            } else {
                setStringAt(sheet, rowIdx, COL_WEIGHT_NET, DEFAULT_NA);
                setStringAt(sheet, rowIdx, COL_WEIGHT_GROSS, DEFAULT_NA);
            }

            BigDecimal cbm = cbmByVehicleId.get(v.getId());
            if (cbm != null) {
                setNumericAt(sheet, rowIdx, COL_CBM, cbm.doubleValue());
                totalCbm = totalCbm.add(cbm);
                hasCbm = true;
            } else {
                setStringAt(sheet, rowIdx, COL_CBM, DEFAULT_NA);
            }

            Long price = crv.getPrice() != null ? crv.getPrice() : v.getPurchasePrice();
            if (price != null) {
                setNumericAt(sheet, rowIdx, COL_UNIT_PRICE, price.doubleValue());
                setNumericAt(sheet, rowIdx, COL_QTY, 1d);
                setNumericAt(sheet, rowIdx, COL_AMOUNT, price.doubleValue());
                totalAmount = totalAmount.add(BigDecimal.valueOf(price));
                hasAmount = true;
            } else {
                setStringAt(sheet, rowIdx, COL_UNIT_PRICE, DEFAULT_NA);
                setNumericAt(sheet, rowIdx, COL_QTY, 1d);
                setStringAt(sheet, rowIdx, COL_AMOUNT, DEFAULT_NA);
            }
            totalQty += 1;
        }

        setTotalNumeric(sheet, COL_WEIGHT_NET, totalNetWeight, hasWeight);
        setTotalNumeric(sheet, COL_WEIGHT_GROSS, totalGrossWeight, hasWeight);
        setTotalNumeric(sheet, COL_CBM, totalCbm, hasCbm);
        if (totalQty > 0) {
            setNumericAt(sheet, TOTAL_ROW, COL_QTY, totalQty);
        } else {
            setStringAt(sheet, TOTAL_ROW, COL_QTY, DEFAULT_NA);
        }
        setTotalNumeric(sheet, COL_AMOUNT, totalAmount, hasAmount);
    }

    /**
     * Freight Charge(Row 25) / Insurance Premium(Row 26) 합산 기입.
     * 차량별 shippingFee, insuranceFee, otherFee를 합산하여 Y열에 기입.
     */
    private void fillFreightAndInsurance(Sheet sheet, List<CustomsRequestVehicle> crvs) {
        long totalFreight = 0;
        long totalInsurance = 0;
        for (CustomsRequestVehicle crv : crvs) {
            if (crv.getShippingFee() != null) totalFreight += crv.getShippingFee();
            if (crv.getInsuranceFee() != null) totalInsurance += crv.getInsuranceFee();
            if (crv.getOtherFee() != null) totalInsurance += crv.getOtherFee();
        }
        if (totalFreight > 0) {
            setNumericAt(sheet, FREIGHT_CHARGE_ROW, COL_FEE_AMOUNT, (double) totalFreight);
        } else {
            clearCell(sheet, FREIGHT_CHARGE_ROW, COL_FEE_AMOUNT);
        }
        if (totalInsurance > 0) {
            setNumericAt(sheet, INSURANCE_PREMIUM_ROW, COL_FEE_AMOUNT, (double) totalInsurance);
        } else {
            clearCell(sheet, INSURANCE_PREMIUM_ROW, COL_FEE_AMOUNT);
        }
    }

    private void setTotalNumeric(Sheet sheet, int colIdx, BigDecimal value, boolean hasValue) {
        if (!hasValue || value == null) {
            setStringAt(sheet, TOTAL_ROW, colIdx, DEFAULT_NA);
            return;
        }
        setNumericAt(sheet, TOTAL_ROW, colIdx, value.doubleValue());
    }

    private void applySignatureImage(Sheet sheet, Workbook wb, byte[] signaturePng) {
        removeTemplatePictures(sheet);

        if (signaturePng == null || signaturePng.length == 0) {
            return;
        }

        int pictureIdx = wb.addPicture(signaturePng, Workbook.PICTURE_TYPE_PNG);
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = new XSSFClientAnchor(
                SIGN_DX1, SIGN_DY1, SIGN_DX2, SIGN_DY2,
                SIGN_COL1, SIGN_ROW1, SIGN_COL2, SIGN_ROW2
        );
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        drawing.createPicture(anchor, pictureIdx);
    }

    private void removeTemplatePictures(Sheet sheet) {
        if (!(sheet instanceof org.apache.poi.xssf.usermodel.XSSFSheet xssfSheet)) {
            return;
        }
        XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
        if (drawing == null) {
            return;
        }
        drawing.getCTDrawing().getTwoCellAnchorList().clear();
    }

    private static void setString(Sheet sheet, String addr, String value) {
        CellReference ref = new CellReference(addr);
        setStringAt(sheet, ref.getRow(), ref.getCol(), value);
    }

    private static void setNumeric(Sheet sheet, String addr, double value) {
        CellReference ref = new CellReference(addr);
        setNumericAt(sheet, ref.getRow(), ref.getCol(), value);
    }

    private static void setStringAt(Sheet sheet, int rowIdx, int colIdx, String value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    private static void setNumericAt(Sheet sheet, int rowIdx, int colIdx, double value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    private static void setNumericNullable(Sheet sheet, int rowIdx, int colIdx, Number value) {
        if (value == null) {
            clearCell(sheet, rowIdx, colIdx);
            return;
        }
        setNumericAt(sheet, rowIdx, colIdx, value.doubleValue());
    }

    private static void setNumericOrNa(Sheet sheet, int rowIdx, int colIdx, Number value) {
        if (value == null) {
            setStringAt(sheet, rowIdx, colIdx, DEFAULT_NA);
            return;
        }
        setNumericAt(sheet, rowIdx, colIdx, value.doubleValue());
    }

    private static void clearCell(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return;
        cell.setBlank();
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    /**
     * HS6 매핑 규칙 (중고차 표 기준, 8703 계열 우선).
     * - 가솔린(점화식): 870321/22/23/24
     * - 디젤(압축점화): 870331/32/33
     * - 전기차: 870390
     */
    private static String resolveHs6(Vehicle vehicle) {
        String fuel = normalize(vehicle.getFuelType());
        Integer cc = vehicle.getDisplacement();

        if (containsAny(fuel, "전기", "electric", "ev", "bev")) {
            return "870390";
        }
        if (containsAny(fuel, "디젤", "diesel", "경유")) {
            if (cc == null) return "870332";
            if (cc <= 1500) return "870331";
            if (cc <= 2500) return "870332";
            return "870333";
        }
        if (containsAny(fuel, "가솔린", "gasoline", "휘발유", "petrol")) {
            if (cc == null) return DEFAULT_HS6_FALLBACK;
            if (cc <= 1000) return "870321";
            if (cc <= 1500) return "870322";
            if (cc <= 3000) return "870323";
            return "870324";
        }

        // 연료 미기입/비표준 문자열일 때는 배기량 기반으로 8703 가솔린 규칙에 매핑합니다.
        if (cc != null) {
            if (cc <= 1000) return "870321";
            if (cc <= 1500) return "870322";
            if (cc <= 3000) return "870323";
            return "870324";
        }
        return DEFAULT_HS6_FALLBACK;
    }

    /**
     * HS CODE는 10자리로 고정 (예: 8703239090).
     */
    private static String resolveHs10(Vehicle vehicle) {
        String hs6 = resolveHs6(vehicle);
        String digits = hs6 == null ? "" : hs6.replaceAll("[^0-9]", "");
        if (digits.length() < 6) {
            digits = String.format("%-6s", digits).replace(' ', '0');
        } else if (digits.length() > 6) {
            digits = digits.substring(0, 6);
        }
        return digits + DEFAULT_HS10_SUFFIX;
    }

    private static String resolveHs10OrNa(Vehicle vehicle) {
        if (vehicle == null) return DEFAULT_NA;
        if (isBlank(vehicle.getFuelType()) && vehicle.getDisplacement() == null) {
            return DEFAULT_NA;
        }
        return resolveHs10(vehicle);
    }

    private static String resolveInvoiceNo(Vehicle firstVehicle, CustomsRequestVehicle firstCrv) {
        if (firstCrv != null) {
            CustomsRequest request = firstCrv.getCustomsRequest();
            if (request != null && !isBlank(request.getInvoiceNo())) {
                return request.getInvoiceNo().trim();
            }
        }
        if (firstVehicle != null && !isBlank(firstVehicle.getExportInvoiceNo())) {
            return firstVehicle.getExportInvoiceNo().trim();
        }
        return "AUTO";
    }

    private static String valueOrNa(String value) {
        if (isBlank(value)) return DEFAULT_NA;
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static boolean containsAny(String text, String... terms) {
        for (String t : terms) {
            if (text.contains(t.toLowerCase())) return true;
        }
        return false;
    }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
