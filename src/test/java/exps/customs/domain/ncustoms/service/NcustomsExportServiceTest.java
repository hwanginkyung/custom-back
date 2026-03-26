package exps.customs.domain.ncustoms.service;

import exps.customs.domain.login.repository.UserRepository;
import exps.customs.domain.ncustoms.dto.CreateNcustomsContainerTempSaveRequest;
import exps.customs.domain.ncustoms.dto.CreateNcustomsExportRequest;
import exps.customs.domain.ncustoms.dto.NcustomsContainerTempSaveResponse;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NcustomsExportServiceTest {

    @Test
    void createExport_whenCreateDisabled_thenThrowsForbidden() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        UserRepository userRepository = mock(UserRepository.class);
        NcustomsExportService service = new NcustomsExportService(dataSource, userRepository);
        ReflectionTestUtils.setField(service, "createEnabled", false);

        assertThatThrownBy(() -> service.createExport(new CreateNcustomsExportRequest(), null, "tester@jinsol.co.kr"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(dataSource, never()).getConnection();
    }

    @Test
    void createTempSaveWithContainer_success() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        UserRepository userRepository = mock(UserRepository.class);
        Connection connection = mock(Connection.class);
        List<String> sqlHistory = new ArrayList<>();

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            sqlHistory.add(sql);
            return preparedStatementForSuccess(sql);
        });

        NcustomsExportService service = new NcustomsExportService(dataSource, userRepository);
        ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 10);

        CreateNcustomsContainerTempSaveRequest request = sampleRequest();
        NcustomsContainerTempSaveResponse response = service.createTempSaveWithContainer(request, null, "tester@jinsol.co.kr");

        assertThat(response.getExpoKey()).isEqualTo("20264000017");
        assertThat(response.getExpoJechlNo()).isEqualTo("400011");
        assertThat(response.getLanNo()).isEqualTo("001");
        assertThat(response.getHangNo()).isEqualTo("01");
        assertThat(response.getContainerNo()).isEqualTo("HSLU5006457");
        assertThat(response.getAddDtTime()).matches("\\d{14}");

        verify(connection).commit();
        verify(connection, never()).rollback();

        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("INSERT INTO EXPO1"));
        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("INSERT INTO EXPO2"));
        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("INSERT INTO EXPO3"));
        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("INSERT INTO EXPCAR"));
        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("INSERT INTO EXCON"));
        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("INSERT INTO EXPO3_FT"));
        assertThat(sqlHistory).anyMatch(sql -> sql.toUpperCase().contains("SELECT RELEASE_LOCK"));
    }

    @Test
    void createTempSaveWithContainer_whenLockFails_thenThrowsInvalidInput() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        UserRepository userRepository = mock(UserRepository.class);
        Connection connection = mock(Connection.class);
        List<String> sqlHistory = new ArrayList<>();

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            sqlHistory.add(sql);
            PreparedStatement ps = mock(PreparedStatement.class);

            if (sql.contains("SELECT GET_LOCK")) {
                ResultSet rs = singleIntResultSet(0);
                when(ps.executeQuery()).thenReturn(rs);
            } else {
                when(ps.executeUpdate()).thenReturn(1);
            }
            return ps;
        });

        NcustomsExportService service = new NcustomsExportService(dataSource, userRepository);
        ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 10);

        assertThatThrownBy(() -> service.createTempSaveWithContainer(sampleRequest(), null, "tester@jinsol.co.kr"))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        verify(connection, never()).commit();
        assertThat(sqlHistory).noneMatch(sql -> sql.toUpperCase().contains("SELECT PNO_EXPO"));
    }

    private static PreparedStatement preparedStatementForSuccess(String sql) throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeUpdate()).thenReturn(1);

        if (sql.contains("SELECT GET_LOCK")) {
            ResultSet rs = singleIntResultSet(1);
            when(ps.executeQuery()).thenReturn(rs);
        } else if (sql.contains("SELECT pno_expo")) {
            ResultSet rs = singleStringResultSet("000016");
            when(ps.executeQuery()).thenReturn(rs);
        } else if (sql.contains("SELECT no_expo")) {
            ResultSet rs = singleStringResultSet("400010");
            when(ps.executeQuery()).thenReturn(rs);
        } else if (sql.contains("SELECT COALESCE(MAX(expo_cnt), 0) + 1")) {
            ResultSet rs = singleIntResultSet(1);
            when(ps.executeQuery()).thenReturn(rs);
        } else if (sql.contains("SELECT RELEASE_LOCK")) {
            ResultSet rs = singleIntResultSet(1);
            when(ps.executeQuery()).thenReturn(rs);
        } else if (sql.trim().toUpperCase().startsWith("SELECT")) {
            ResultSet rs = emptyResultSet();
            when(ps.executeQuery()).thenReturn(rs);
        }

        return ps;
    }

    private static ResultSet singleIntResultSet(int value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getInt(1)).thenReturn(value);
        return rs;
    }

    private static ResultSet singleStringResultSet(String value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn(value);
        return rs;
    }

    private static ResultSet emptyResultSet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static CreateNcustomsContainerTempSaveRequest sampleRequest() {
        CreateNcustomsContainerTempSaveRequest req = new CreateNcustomsContainerTempSaveRequest();
        set(req, "year", "2026");
        set(req, "userCode", "4");
        set(req, "singoDate", "20260303");
        set(req, "segwan", "020");
        set(req, "gwa", "09");
        set(req, "suchuljaCode", "0006");
        set(req, "suchuljaSangho", "Exporter");
        set(req, "suchuljaGbn", "C");
        set(req, "whajuCode", "000G");
        set(req, "whajuSangho", "Whaju");
        set(req, "whajuTong", "01099990000");
        set(req, "whajuSaup", "0000000000");
        set(req, "gumaejaCode", "INIDSYST0001C");
        set(req, "gumaejaSangho", "A ID SYSTEMS I PVT LTD");
        set(req, "trustCode", "0006");
        set(req, "trustSangho", "Broker");
        set(req, "trustJuso", "Broker Address");
        set(req, "trustName", "Manager");
        set(req, "trustTong", "0101151029");
        set(req, "trustSaup", "7938500297");
        set(req, "trustPost", "04530");
        set(req, "trustJuso2", "Detail Address");
        set(req, "trustRoadCd", "02012014");
        set(req, "trustBuildMngNo", "02012014");
        set(req, "postCode", "22031");
        set(req, "juso", "Main Address");
        set(req, "locationAddr", "Detail Location");
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
        set(req, "writerId", "tester");
        set(req, "writerName", "Tester");
        return req;
    }

    private static void set(Object target, String fieldName, Object value) {
        ReflectionTestUtils.setField(target, fieldName, value);
    }
}
