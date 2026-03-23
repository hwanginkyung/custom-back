package exps.cariv.domain.customs.entity;

public enum InvoiceNumberType {
    RORO("RR"),
    CONTAINER("CT"),
    MALSO("MS");

    private final String code;

    InvoiceNumberType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InvoiceNumberType fromShippingMethod(ShippingMethod method) {
        if (method == ShippingMethod.CONTAINER) {
            return CONTAINER;
        }
        return RORO;
    }
}
