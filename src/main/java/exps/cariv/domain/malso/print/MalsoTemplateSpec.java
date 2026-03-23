package exps.cariv.domain.malso.print;

/**
 * 말소등록신청서 템플릿(말소신청서_A4_수정본_v3.xlsx)의 셀 좌표.
 * 템플릿을 수정하면 여기 좌표만 바꿔주면 됩니다.
 */
public final class MalsoTemplateSpec {

    private MalsoTemplateSpec() {}

    // --- 소유자 ---
    public static final String OWNER_NAME = "F7";    // (merged: F7:K7)
    public static final String OWNER_IDNO = "F8";    // (merged: F8:J8)
    public static final String OWNER_ADDR = "F9";    // (merged: F9:K9)

    // --- 차량 ---
    public static final String VEHICLE_REG_NO = "A12";   // (merged: A12:E12)
    public static final String VEHICLE_CHASSIS = "F12";  // (merged: F12:I12)
    public static final String VEHICLE_MILEAGE = "J12";  // (merged: J12:K12)

    // --- 신청인(수임자) ---
    public static final String APPLICATION_DATE = "J26"; // (merged: J26:K26)
    public static final String APPLICANT_NAME = "G29";
    public static final String APPLICANT_BIRTH = "G30";

    // --- 위임장(위임자) ---
    public static final String POA_NAME  = "E34";  // (merged: E34:K34)
    public static final String POA_REP_NAME = "E35"; // (merged: E35:K35)
    public static final String POA_BIZNO = "E36"; // (merged: E36:K36)
    public static final String POA_ADDR  = "E37"; // (merged: E37:K37)
}
