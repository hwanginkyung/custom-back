package exps.cariv.domain.vehicle.dto.response;

/**
 * 환급 엑셀 미리보기 — 한 행.
 * 엑셀 컬럼: No, 매입일, 차량번호, 차량명, 차대번호, 소유자, 소유자 주민번호, 공급자(소유자) 주소, 매입금액, 수량
 */
public record RefundExcelPreviewRow(
        int no,
        String purchaseDate,
        String vehicleNo,
        String modelName,
        String vin,
        String ownerName,
        String ownerIdNumber,
        String ownerAddress,
        Long purchasePrice,
        String quantity
) {}
