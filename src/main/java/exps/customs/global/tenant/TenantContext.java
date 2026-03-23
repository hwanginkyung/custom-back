package exps.customs.global.tenant;

public class TenantContext {
    private static final ThreadLocal<Long> COMPANY_ID = new ThreadLocal<>();

    public static void setCompanyId(Long companyId) { COMPANY_ID.set(companyId); }
    public static Long getCompanyId() { return COMPANY_ID.get(); }
    public static void clear() { COMPANY_ID.remove(); }
}
