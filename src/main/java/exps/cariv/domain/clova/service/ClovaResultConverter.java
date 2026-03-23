package exps.cariv.domain.clova.service;

import exps.cariv.domain.clova.dto.VehicleDeregistration;
import exps.cariv.domain.clova.dto.VehicleRegistration;
import exps.cariv.domain.malso.dto.ParsedDereg;
import exps.cariv.domain.registration.dto.RegistrationParsed;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * newv(CLOVA+규칙파서) 결과 DTO → cariv 기존 DTO 변환 레이어.
 * <p>프론트/Vehicle 엔티티가 사용하는 DTO 구조는 그대로 유지하면서,
 * 내부 OCR 엔진만 교체하기 위한 어댑터.</p>
 */
@Slf4j
public class ClovaResultConverter {

    private ClovaResultConverter() {}

    // ========================================
    // 등록증: VehicleRegistration → RegistrationParsed
    // ========================================
    public static RegistrationParsed toRegistrationParsed(VehicleRegistration src) {
        if (src == null) return null;

        return new RegistrationParsed(
                src.getVin(),
                src.getVehicleNo(),
                src.getCarType(),
                src.getVehicleUse(),
                src.getModelName(),
                src.getEngineType(),
                null, // mileageKm: 등록증에 보통 없음
                cleanOwnerName(src.getOwnerName()),
                src.getOwnerId(),
                src.getModelYear(),
                src.getFuelType(),                        // → fuelType
                src.getManufactureYearMonth(),            // → manufactureYearMonth
                src.getDisplacement(),
                parseDate(src.getFirstRegistratedAt()),
                src.getModelCode(),
                src.getAddress(),                    // → addressText
                parseIntFromString(src.getLengthVal()),   // → lengthMm
                parseIntFromString(src.getWidthVal()),    // → widthMm
                parseIntFromString(src.getHeightVal()),   // → heightMm
                parseKg(src.getWeight()),                 // → weightKg
                parseIntFromString(src.getSeating()),     // → seating
                parseKg(src.getMaxLoad()),                // → maxLoadKg
                parsePower(src.getPower())                // → powerKw
        );
    }

    // ========================================
    // 말소증: VehicleDeregistration → ParsedDereg
    // ========================================
    public static ParsedDereg toParsedDereg(VehicleDeregistration src) {
        if (src == null) return null;

        String rightsRelation = buildRightsRelation(
                src.getSeizureCount(), src.getMortgageCount());

        return new ParsedDereg(
                src.getVin(),
                src.getVehicleNo(),                  // → vehicleNo
                null,                                 // documentNo: CLOVA 파서에서 미추출
                src.getSpecManagementNo(),            // → specNo
                src.getVehicleNo(),                   // → registrationNo (같은 값)
                parseDate(src.getDeregistrationDate()), // → deRegistrationDate
                src.getDeregistrationReason(),         // → deRegistrationReason
                rightsRelation,                        // → rightsRelation ("압류 N건, 저당 M건")
                src.getModelName(),
                src.getModelYear(),
                cleanOwnerName(src.getOwnerName()),
                src.getOwnerId(),
                parseLong(src.getMileage())             // → mileageKm
        );
    }

    // ========================================
    // 변환 유틸
    // ========================================

    /**
     * "2024-01-10" 등의 문자열을 LocalDate로 변환.
     */
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim().replaceAll("[^0-9\\-]", "");
        try {
            // yyyy-MM-dd
            if (trimmed.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                return LocalDate.parse(trimmed);
            }
        } catch (DateTimeParseException e) {
            log.debug("날짜 파싱 실패: {}", s);
        }
        return null;
    }

    /**
     * "1895kg", "1895", "1,895kg" → 1895
     */
    private static Integer parseKg(String s) {
        if (s == null || s.isBlank()) return null;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "224/5600" → 224 (마력/RPM 중 마력만)
     * "224" → 224
     */
    private static Integer parsePower(String s) {
        if (s == null || s.isBlank()) return null;
        String value = s.trim();
        if (value.contains("/")) {
            value = value.substring(0, value.indexOf("/"));
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "5", "4820" 등 → Integer
     */
    private static Integer parseIntFromString(String s) {
        if (s == null || s.isBlank()) return null;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "12345" → 12345L
     */
    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "(상품용)", "*상품용+" 등을 제거한 소유자명 반환.
     */
    private static String cleanOwnerName(String name) {
        if (name == null || name.isBlank()) return null;
        String cleaned = name
                .replaceAll("[(*]\\s*상품용\\s*[)*+]", "")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * "압류 0건, 저당 0건" 형식으로 조합.
     */
    private static String buildRightsRelation(String seizure, String mortgage) {
        if ((seizure == null || seizure.isBlank()) &&
                (mortgage == null || mortgage.isBlank())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (seizure != null && !seizure.isBlank()) {
            sb.append("압류 ").append(seizure.replaceAll("[^0-9]", "")).append("건");
        }
        if (mortgage != null && !mortgage.isBlank()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("저당 ").append(mortgage.replaceAll("[^0-9]", "")).append("건");
        }
        return sb.toString();
    }
}
