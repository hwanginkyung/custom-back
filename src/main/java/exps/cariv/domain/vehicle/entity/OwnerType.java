package exps.cariv.domain.vehicle.entity;

public enum OwnerType {
    INDIVIDUAL("개인", true),
    DEALER_INDIVIDUAL("매매상사(개인)", true),
    DEALER_CORPORATE("매매상사(법인)", false),
    CORPORATE_OTHER("그 밖의 법인", false);

    private final String label;
    private final boolean ownerIdCardRequired;

    OwnerType(String label, boolean ownerIdCardRequired) {
        this.label = label;
        this.ownerIdCardRequired = ownerIdCardRequired;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOwnerIdCardRequired() {
        return ownerIdCardRequired;
    }

    public static OwnerType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("소유자유형은 필수입니다.");
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "INDIVIDUAL", "개인" -> INDIVIDUAL;
            case "DEALER_INDIVIDUAL", "DEALER_PERSONAL", "매매상사(개인)" -> DEALER_INDIVIDUAL;
            case "DEALER_CORPORATE", "매매상사(법인)" -> DEALER_CORPORATE;
            case "CORPORATE_OTHER", "OTHER_CORPORATE", "그 밖의 법인", "기타법인" -> CORPORATE_OTHER;
            default -> throw new IllegalArgumentException("지원하지 않는 소유자유형입니다: " + raw);
        };
    }
}
