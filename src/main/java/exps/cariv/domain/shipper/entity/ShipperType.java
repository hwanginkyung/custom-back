package exps.cariv.domain.shipper.entity;

public enum ShipperType {
    INDIVIDUAL_BUSINESS("개인사업자"),
    CORPORATE_BUSINESS("법인사업자");

    private final String label;

    ShipperType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ShipperType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("화주유형은 필수입니다.");
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "INDIVIDUAL_BUSINESS", "INDIVIDUAL", "PERSONAL", "개인사업자" -> INDIVIDUAL_BUSINESS;
            case "CORPORATE_BUSINESS", "CORPORATE", "법인사업자" -> CORPORATE_BUSINESS;
            default -> throw new IllegalArgumentException("지원하지 않는 화주유형입니다: " + raw);
        };
    }
}
