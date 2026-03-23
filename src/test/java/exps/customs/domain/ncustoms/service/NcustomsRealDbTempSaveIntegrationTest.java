package exps.customs.domain.ncustoms.service;

import com.zaxxer.hikari.HikariDataSource;
import exps.customs.domain.ncustoms.dto.CreateNcustomsContainerTempSaveRequest;
import exps.customs.domain.ncustoms.dto.NcustomsContainerTempSaveResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class NcustomsRealDbTempSaveIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "NCUSTOMS_REALDB_TEST", matches = "true")
    void shouldInsertTempSaveWithContainerIntoRealNcustomsDb() throws Exception {
        String todayYmd = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(envOrDefault(
                "NCUSTOMS_DB_URL",
                "jdbc:mysql://192.168.0.46:53306/ncustoms?useUnicode=true&characterEncoding=EUC-KR&serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true"
        ));
        dataSource.setUsername(envOrDefault("NCUSTOMS_DB_USERNAME", "kcba"));
        dataSource.setPassword(envOrDefault("NCUSTOMS_DB_PASSWORD", "kcbapasswd"));
        dataSource.setDriverClassName(envOrDefault("NCUSTOMS_DB_DRIVER", "com.mysql.cj.jdbc.Driver"));
        dataSource.setConnectionInitSql(envOrDefault("NCUSTOMS_DB_INIT_SQL", "SET NAMES euckr"));
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(0);

        try {
            NcustomsExportService service = new NcustomsExportService(dataSource);
            ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 10);

            CreateNcustomsContainerTempSaveRequest req = new CreateNcustomsContainerTempSaveRequest();
            set(req, "year", "2026");
            set(req, "userCode", "4");
            set(req, "singoDate", todayYmd);
            set(req, "segwan", "020");
            set(req, "gwa", "09");
            set(req, "suchuljaCode", "0006");
            set(req, "suchuljaSangho", "(주)테스트수출자");
            set(req, "suchuljaGbn", "C");
            set(req, "whajuCode", "000G");
            set(req, "whajuSangho", "테스트화주");
            set(req, "whajuTong", "01099990000");
            set(req, "whajuSaup", "0000000000");
            set(req, "gumaejaCode", "INIDSYST0001C");
            set(req, "gumaejaSangho", "A ID SYSTEMS I PVT LTD");
            set(req, "trustCode", "0006");
            set(req, "trustSangho", "(주)테스트관세사");
            set(req, "trustJuso", "서울시 테스트로 77");
            set(req, "trustName", "담당자");
            set(req, "trustTong", "0101151029");
            set(req, "trustSaup", "7938500297");
            set(req, "trustPost", "04530");
            set(req, "trustJuso2", "상세주소");
            set(req, "trustRoadCd", "02012014");
            set(req, "trustBuildMngNo", "02012014");
            set(req, "postCode", "22031");
            set(req, "juso", "인천 테스트주소 777");
            set(req, "locationAddr", "상세주소");
            set(req, "ivNo", "KYR0622");
            set(req, "lanNo", "001");
            set(req, "hangNo", "01");
            set(req, "containerSeqNo", "001");
            set(req, "containerNo", "HSLU5006457");
            set(req, "hsCode", "8703239020");
            set(req, "itemName", "USED CAR");
            set(req, "itemNameLine1", "KIA K7 2020-02Y");
            set(req, "itemNameLine3", "2,497cc Gasoline");
            set(req, "qty", BigDecimal.ONE);
            set(req, "totalWeight", new BigDecimal("1660"));
            set(req, "weightUnit", "KG");
            set(req, "packageCount", BigDecimal.ONE);
            set(req, "packageUnit", "OU");
            set(req, "gyeljeMoney", "USD");
            set(req, "usdExch", new BigDecimal("1439.26"));
            set(req, "gyeljeInput", new BigDecimal("14200"));
            set(req, "indojo", "FOB");
            set(req, "originCountry", "KR");
            set(req, "mokjukCode", "KG");
            set(req, "mokjukName", "KYRGY");
            set(req, "hangguCode", "KRINC");
            set(req, "hangguName", "INCHEON");
            set(req, "unsongType", "10");
            set(req, "unsongBox", "LC");
            set(req, "procGbn", "AUTO");
            set(req, "singoGbn", "B");
            set(req, "eventType", "A");
            set(req, "southNorthTradeYn", "Y");
            set(req, "writerId", "payload-writer");
            set(req, "writerName", "PAYLOAD WRITER");

            NcustomsContainerTempSaveResponse result = service.createTempSaveWithContainer(req, "apiuser");
            assertThat(result.getExpoKey()).isNotBlank();
            assertThat(result.getExpoJechlNo()).isNotBlank();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT expo_key, expo_save_gbn, expo_iv_no, expo_singo_date,
                                expo_suchulja_sangho, expo_whaju_sangho, expo_trust_sangho, expo_gumaeja_sangho,
                                UserID, UserNM
                         FROM expo1
                         WHERE expo_key=?
                         """)) {
                ps.setString(1, result.getExpoKey());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("expo_key")).isEqualTo(result.getExpoKey());
                    assertThat(rs.getString("expo_save_gbn")).isEqualTo("N");
                    assertThat(rs.getString("expo_iv_no")).isEqualTo("KYR0622");
                    assertThat(rs.getString("expo_singo_date")).isEqualTo(todayYmd);
                    assertThat(rs.getString("UserID")).isEqualTo("apiuser");
                    assertThat(rs.getString("UserNM")).isEqualTo("apiuser");

                    String expectedSuchuljaSangho = queryOptionalString(dataSource, "SELECT Deal_sangho FROM DDeal WHERE Deal_code=?", "0006");
                    if (expectedSuchuljaSangho != null && !expectedSuchuljaSangho.isBlank()) {
                        assertThat(rs.getString("expo_suchulja_sangho")).isEqualTo(expectedSuchuljaSangho);
                    }

                    String expectedWhajuSangho = queryOptionalString(dataSource, "SELECT Deal_sangho FROM DDeal WHERE Deal_code=?", "000G");
                    if (expectedWhajuSangho != null && !expectedWhajuSangho.isBlank()) {
                        assertThat(rs.getString("expo_whaju_sangho")).isEqualTo(expectedWhajuSangho);
                    }

                    String expectedTrustSangho = queryOptionalString(dataSource, "SELECT Deal_sangho FROM DDeal WHERE Deal_code=?", "0006");
                    if (expectedTrustSangho != null && !expectedTrustSangho.isBlank()) {
                        assertThat(rs.getString("expo_trust_sangho")).isEqualTo(expectedTrustSangho);
                    }

                    String expectedGumaejaSangho = queryOptionalString(dataSource, "SELECT Gonggub_sangho FROM Dgonggub WHERE Gonggub_code=?", "INIDSYST0001C");
                    if (expectedGumaejaSangho != null && !expectedGumaejaSangho.isBlank()) {
                        assertThat(rs.getString("expo_gumaeja_sangho")).isEqualTo(expectedGumaejaSangho);
                    }
                }
            }

            assertThat(queryCount(dataSource, "SELECT COUNT(*) FROM expo2 WHERE exlan_key=?", result.getExpoKey())).isEqualTo(1);
            assertThat(queryCount(dataSource, "SELECT COUNT(*) FROM expo3 WHERE expum_key=?", result.getExpoKey())).isEqualTo(1);
            assertThat(queryCount(dataSource, "SELECT COUNT(*) FROM expcar WHERE expo5_key=?", result.getExpoKey())).isEqualTo(1);
            assertThat(queryCount(dataSource, "SELECT COUNT(*) FROM excon WHERE excon_key=?", result.getExpoKey())).isEqualTo(1);
            assertThat(queryCount(dataSource, "SELECT COUNT(*) FROM expo3_ft WHERE ft_key LIKE CONCAT(?, '%')", result.getExpoKey())).isGreaterThanOrEqualTo(1);

            System.out.println("[REAL-DB-INSERT-OK] expoKey=" + result.getExpoKey() + ", jechlNo=" + result.getExpoJechlNo());
        } finally {
            dataSource.close();
        }
    }

    private static String envOrDefault(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static void set(Object target, String fieldName, Object value) {
        ReflectionTestUtils.setField(target, fieldName, value);
    }

    private static int queryCount(HikariDataSource dataSource, String sql, String key) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String queryOptionalString(HikariDataSource dataSource, String sql, String key) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        }
    }
}
