package exps.customs.domain.ncustoms.service;

import exps.customs.domain.ncustoms.dto.CreateNcustomsContainerTempSaveRequest;
import exps.customs.domain.ncustoms.dto.CreateNcustomsExportRequest;
import exps.customs.domain.ncustoms.dto.NcustomsContainerTempSaveResponse;
import exps.customs.domain.ncustoms.dto.NcustomsExportResponse;
import exps.customs.domain.ncustoms.dto.NcustomsShipperResponse;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ncustoms.datasource", name = "enabled", havingValue = "true")
public class NcustomsExportService {

    private static final DateTimeFormatter YMD_HMS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern SERIAL_PATTERN = Pattern.compile("^([A-Za-z]*)(\\d+)$");
    private static final int SERIAL_MAX_RETRY = 1000;

    @Qualifier("ncustomsDataSource")
    private final DataSource ncustomsDataSource;

    @Value("${ncustoms.export.lock-timeout-seconds:10}")
    private int lockTimeoutSeconds;

    @Value("${ncustoms.export.create-enabled:false}")
    private boolean createEnabled;

    public List<NcustomsShipperResponse> getShippers(String codePrefix, String keyword, Integer limit) {
        String prefix = (codePrefix == null || codePrefix.isBlank()) ? "00" : codePrefix.trim();
        int safeLimit = (limit == null) ? 100 : Math.max(1, Math.min(limit, 500));
        boolean hasKeyword = keyword != null && !keyword.isBlank();

        StringBuilder sql = new StringBuilder("""
                SELECT deal_code, Deal_saupgbn, Deal_saup, Deal_sangho, Deal_name,
                       Deal_post, deal_juso, Deal_juso2,
                       Deal_tel, Deal_fax, Deal_tong,
                       ROAD_NM_CD, BULD_MNG_NO,
                       AddDtTime, EditDtTime
                FROM DDeal
                WHERE deal_code LIKE ?
                """);
        if (hasKeyword) {
            sql.append(" AND (Deal_sangho LIKE ? OR Deal_name LIKE ?) ");
        }
        sql.append(" ORDER BY deal_code LIMIT ?");

        List<NcustomsShipperResponse> results = new ArrayList<>();
        try (Connection conn = ncustomsDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            conn.setAutoCommit(true);
            int idx = 1;
            ps.setString(idx++, prefix + "%");
            if (hasKeyword) {
                String k = "%" + keyword.trim() + "%";
                ps.setString(idx++, k);
                ps.setString(idx++, k);
            }
            ps.setInt(idx, safeLimit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(NcustomsShipperResponse.builder()
                            .dealCode(rs.getString("deal_code"))
                            .dealSaupgbn(rs.getString("Deal_saupgbn"))
                            .dealSaup(rs.getString("Deal_saup"))
                            .dealSangho(rs.getString("Deal_sangho"))
                            .dealName(rs.getString("Deal_name"))
                            .dealPost(rs.getString("Deal_post"))
                            .dealJuso(rs.getString("deal_juso"))
                            .dealJuso2(rs.getString("Deal_juso2"))
                            .dealTel(rs.getString("Deal_tel"))
                            .dealFax(rs.getString("Deal_fax"))
                            .dealTong(rs.getString("Deal_tong"))
                            .roadNmCd(rs.getString("ROAD_NM_CD"))
                            .buldMngNo(rs.getString("BULD_MNG_NO"))
                            .addDtTime(rs.getString("AddDtTime"))
                            .editDtTime(rs.getString("EditDtTime"))
                            .build());
                }
            }
            return results;
        } catch (Exception e) {
            log.error("[NCustoms] failed to get shippers prefix={}, keyword={}", prefix, keyword, e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to load shippers");
        }
    }

    public NcustomsExportResponse createExport(CreateNcustomsExportRequest req, String actorLoginId) {
        if (!createEnabled) {
            throw new CustomException(ErrorCode.FORBIDDEN, "create export is disabled. use temp-save only");
        }

        String lockKey = "ncustoms:expo:" + req.getYear() + ":" + req.getUserCode();
        boolean lockAcquired = false;

        try (Connection conn = ncustomsDataSource.getConnection()) {
            conn.setAutoCommit(false);
            executeUpdate(conn, "SET NAMES euckr");

            lockAcquired = acquireLock(conn, lockKey, lockTimeoutSeconds);
            if (!lockAcquired) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "failed to acquire ncustoms lock");
            }
            try {
                String nextPno = nextAvailablePno(conn, req.getYear(), req.getUserCode());
                String nextDno = nextAvailableDno(conn, req.getYear(), req.getUserCode());
                String expoKey = req.getYear() + req.getUserCode() + nextPno;
                String singoDate = resolveSingoDate(req.getSingoDate());
                DealMaster suchuljaMaster = resolveDealMaster(conn, req.getSuchuljaCode(), req.getSuchuljaSangho(), null, null);
                DealMaster trustMaster = resolveDealMaster(conn, req.getTrustCode(), req.getTrustSangho(), req.getTrustTong(), req.getTrustSaup());
                String gumaejaSangho = resolveGonggubSangho(conn, req.getGumaejaCode(), req.getGumaejaSangho());
                String writerId = resolveWriterId(req.getWriterId(), actorLoginId);
                String writerName = resolveWriterName(req.getWriterName(), actorLoginId);

                String addDtTime = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(YMD_HMS);

                executeUpdate(conn, """
                        INSERT INTO expo1 (
                            Expo_key, Expo_year, Expo_jechlno,
                            Expo_chk_dg, Expo_save_gbn, Expo_local_gubun,
                            Expo_singo_year, Expo_segwan, Expo_gwa, Expo_singo_date, Expo_singo_gbn,
                            Expo_suchulja_code, Expo_suchulja_sangho, Expo_suchulja_gbn,
                            Expo_gumaeja_code, EXPO_GUMAEJA_SANGHO,
                            EXPO_TRUST_CODE, EXPO_TRUST_SANGHO, EXPO_TRUST_TONG, EXPO_TRUST_SAUP,
                            Expo_gyelje_money, EXPO_USD_EXCH, EXPO_GYELJE_EXCH, EXPO_GYELJE_INPUT,
                            Expo_post, Expo_juso, EXPO_LOCATION_ADDR, expo_trust_post,
                            UserID, UserNM, AddDtTime, EditDtTime, userno
                        ) VALUES (
                            ?, ?, ?,
                            'X', 'N', '',
                            ?, ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, '', ''
                        )
                        """,
                        expoKey, req.getYear(), nextDno,
                        req.getYear().substring(2),
                        req.getSegwan(), req.getGwa(), singoDate, defaultIfBlank(req.getSingoGbn(), "B"),
                        req.getSuchuljaCode(), suchuljaMaster.sangho(), req.getSuchuljaGbn(),
                        req.getGumaejaCode(), gumaejaSangho,
                        req.getTrustCode(), trustMaster.sangho(), trustMaster.tong(), trustMaster.saup(),
                        defaultIfBlank(req.getGyeljeMoney(), "USD"),
                        defaultBigDecimal(req.getUsdExch()), defaultBigDecimal(req.getUsdExch()), defaultBigDecimal(req.getGyeljeInput()),
                        req.getPostCode(), req.getJuso(), req.getLocationAddr(), req.getTrustPost(),
                        writerId, writerName, addDtTime
                );

                int nextExpoCnt = queryInt(conn, "SELECT COALESCE(MAX(expo_cnt), 0) + 1 FROM expodamdang WHERE expo_key=?", expoKey);
                executeUpdate(
                        conn,
                        "INSERT INTO expodamdang (expo_key, expo_cnt, writter_id, writter, write_dttm, procgbn) VALUES (?, ?, ?, ?, ?, ?)",
                        expoKey, nextExpoCnt, writerId, writerName, addDtTime, defaultIfBlank(req.getProcGbn(), "AUTO")
                );

                executeUpdate(conn, "UPDATE expo1 SET userno='' WHERE expo_key=?", expoKey);

                conn.commit();
                log.info("[NCustoms] expo created expoKey={}, jechlNo={}", expoKey, nextDno);

                return NcustomsExportResponse.builder()
                        .expoKey(expoKey)
                        .expoJechlNo(nextDno)
                        .pnoExpo(nextPno)
                        .dnoExpo(nextDno)
                        .addDtTime(addDtTime)
                        .build();
            } catch (CustomException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (Exception e) {
                rollbackQuietly(conn);
                log.error("[NCustoms] failed to create export", e);
                throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "ncustoms export creation failed");
            } finally {
                if (lockAcquired) {
                    releaseLock(conn, lockKey);
                }
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NCustoms] failed to connect ncustoms db (createExport)", e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to connect ncustoms db");
        }
    }

    public NcustomsContainerTempSaveResponse createTempSaveWithContainer(CreateNcustomsContainerTempSaveRequest req, String actorLoginId) {
        String lockKey = "ncustoms:expo:" + req.getYear() + ":" + req.getUserCode();
        boolean lockAcquired = false;

        try (Connection conn = ncustomsDataSource.getConnection()) {
            conn.setAutoCommit(false);
            executeUpdate(conn, "SET NAMES euckr");

            lockAcquired = acquireLock(conn, lockKey, lockTimeoutSeconds);
            if (!lockAcquired) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "failed to acquire ncustoms lock");
            }

            try {
                String nextPno = nextAvailablePno(conn, req.getYear(), req.getUserCode());
                String nextDno = nextAvailableDno(conn, req.getYear(), req.getUserCode());
                String singoDate = resolveSingoDate(req.getSingoDate());
                DealMaster suchuljaMaster = resolveDealMaster(conn, req.getSuchuljaCode(), req.getSuchuljaSangho(), null, null);
                DealMaster whajuMaster = resolveDealMaster(conn, req.getWhajuCode(), req.getWhajuSangho(), req.getWhajuTong(), req.getWhajuSaup());
                DealMaster trustMaster = resolveDealMaster(conn, req.getTrustCode(), req.getTrustSangho(), req.getTrustTong(), req.getTrustSaup());
                String gumaejaSangho = resolveGonggubSangho(conn, req.getGumaejaCode(), req.getGumaejaSangho());
                String writerId = resolveWriterId(req.getWriterId(), actorLoginId);
                String writerName = resolveWriterName(req.getWriterName(), actorLoginId);

                String expoKey = req.getYear() + req.getUserCode() + nextPno;
                String lanNo = defaultIfBlank(req.getLanNo(), "001");
                String hangNo = defaultIfBlank(req.getHangNo(), "01");
                String seqNo = defaultIfBlank(req.getContainerSeqNo(), "001");
                String addDtTime = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(YMD_HMS);

                BigDecimal usdExch = defaultBigDecimal(req.getUsdExch());
                BigDecimal gyeljeInput = defaultBigDecimal(req.getGyeljeInput());
                BigDecimal totalWon = gyeljeInput.multiply(usdExch).setScale(0, RoundingMode.HALF_UP);
                BigDecimal qty = req.getQty() == null ? BigDecimal.ONE : req.getQty();
                BigDecimal totalWeight = req.getTotalWeight() == null ? BigDecimal.ZERO : req.getTotalWeight();
                BigDecimal packageCnt = req.getPackageCount() == null ? BigDecimal.ONE : req.getPackageCount();

                executeUpdate(conn, "DELETE FROM expo2 WHERE exlan_key=?", expoKey);
                executeUpdate(conn, "DELETE FROM expo3 WHERE expum_key=?", expoKey);
                executeUpdate(conn, "DELETE FROM expcar WHERE expo5_key=?", expoKey);
                executeUpdate(conn, "DELETE FROM excon WHERE excon_key=?", expoKey);
                executeUpdate(conn, "DELETE FROM expo3_ft WHERE ft_key LIKE CONCAT(?, '%')", expoKey);
                executeUpdate(conn, "DELETE FROM expo1 WHERE expo_key=?", expoKey);

                executeUpdate(conn, """
                        INSERT INTO expo1 (
                            Expo_key, Expo_year, Expo_jechlno,
                            Expo_chk_dg, Expo_save_gbn, Expo_local_gubun,
                            Expo_singo_year, Expo_segwan, Expo_gwa, Expo_singo_date, Expo_singo_gbn,
                            Expo_singoja_sangho,
                            Expo_suchulja_code, Expo_suchulja_sangho, Expo_suchulja_gbn,
                            Expo_whaju_code, Expo_whaju_sangho, Expo_whaju_tong, Expo_whaju_saup,
                            Expo_gumaeja_code, Expo_gumaeja_sangho,
                            Expo_jong, Expo_gyelje,
                            Expo_mokjuk_code, Expo_mokjuk_name, Expo_hanggu_code, Expo_hanggu_name,
                            Expo_unsong_type, Expo_unsong_box, Expo_jejo_date,
                            Expo_post, Expo_juso, EXPO_LOCATION_ADDR, Expo_iv_no, Expo_lan,
                            EXPO_TOTAL_JUNG, Expo_jung_danwi, EXPO_POJANG_SU,
                            EXPO_TOTAL_WON, EXPO_USD_EXCH, EXPO_TOTAL_USD,
                            Expo_indojo, Expo_gyelje_money, EXPO_GYELJE_EXCH, EXPO_GYELJE_INPUT, Expo_calc_yn,
                            EXPO_TRUST_CODE, EXPO_TRUST_SANGHO, EXPO_TRUST_JUSO, EXPO_TRUST_NAME, EXPO_TRUST_TONG,
                            EXPO_TRUST_SAUP, expo_trust_post, EXPO_TRUST_JUSOD, EXPO_TRUST_ROADCD, EXPO_TRUST_BUILDMNGNO,
                            event_type, Expo_SouthNorthTradeYn,
                            UserID, UserNM, AddDtTime, EditDtTime, userno
                        ) VALUES (
                            ?, ?, ?,
                            'X', 'N', '',
                            ?, ?, ?, ?, ?,
                            ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?,
                            'A', 'TT',
                            ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?, 'O',
                            ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?,
                            ?, ?,
                            ?, ?, ?, '', ''
                        )
                        """,
                        expoKey, req.getYear(), nextDno,
                        req.getYear().substring(2), req.getSegwan(), req.getGwa(), singoDate, defaultIfBlank(req.getSingoGbn(), "B"),
                        suchuljaMaster.sangho(),
                        req.getSuchuljaCode(), suchuljaMaster.sangho(), defaultIfBlank(req.getSuchuljaGbn(), "C"),
                        req.getWhajuCode(), whajuMaster.sangho(), whajuMaster.tong(), whajuMaster.saup(),
                        req.getGumaejaCode(), gumaejaSangho,
                        defaultIfBlank(req.getMokjukCode(), "KG"), defaultIfBlank(req.getMokjukName(), "KYRGY"),
                        defaultIfBlank(req.getHangguCode(), "KRINC"), defaultIfBlank(req.getHangguName(), ""),
                        defaultIfBlank(req.getUnsongType(), "10"), defaultIfBlank(req.getUnsongBox(), "LC"), singoDate,
                        req.getPostCode(), req.getJuso(), req.getLocationAddr(), req.getIvNo(), lanNo,
                        totalWeight, defaultIfBlank(req.getWeightUnit(), "KG"), packageCnt,
                        totalWon, usdExch, gyeljeInput,
                        defaultIfBlank(req.getIndojo(), "FOB"), defaultIfBlank(req.getGyeljeMoney(), "USD"), usdExch, gyeljeInput,
                        req.getTrustCode(), trustMaster.sangho(), req.getTrustJuso(), req.getTrustName(), trustMaster.tong(),
                        trustMaster.saup(), req.getTrustPost(), req.getTrustJuso2(), req.getTrustRoadCd(), req.getTrustBuildMngNo(),
                        defaultIfBlank(req.getEventType(), "A"), defaultIfBlank(req.getSouthNorthTradeYn(), "Y"),
                        writerId, writerName, addDtTime
                );

                int nextExpoCnt = queryInt(conn,
                        "SELECT COALESCE(MAX(expo_cnt), 0) + 1 FROM expodamdang WHERE expo_key=?",
                        expoKey);
                executeUpdate(conn,
                        "INSERT INTO expodamdang (expo_key, expo_cnt, writter_id, writter, write_dttm, procgbn) VALUES (?, ?, ?, ?, ?, ?)",
                        expoKey, nextExpoCnt, writerId, writerName, addDtTime, defaultIfBlank(req.getProcGbn(), "AUTO"));

                executeUpdate(conn, """
                        INSERT INTO expo2 (
                            Exlan_key, Exlan_lan, Exlan_jung_gubun, Exlan_hs, Exlan_jepum_code,
                            Exlan_jung, Exlan_jung_danwi, Exlan_su, Exlan_su_danwi,
                            Exlan_pojang_su, Exlan_pojang_danwi, Exlan_whan_jepum,
                            Exlan_ename, Exlan_egukyk, Exlan_pum1, Exlan_gukyk,
                            Exlan_gyelje_gum, Exlan_gyelje_fob, Exlan_fob_won, Exlan_fob_usd,
                            Exlan_attach_yn, EXLAN_CONT_AUTOLOAD, EXLAN_AGREE_CD, EXLAN_KOSAGBN, EXLAN_KOSAAPPNO,
                            exlan_sangpyo, exlan_wonsanji, exlan_wonsanji_bang, exlan_wonsanji_pyosi, exlan_CoIssueYN
                        ) VALUES (
                            ?, ?, '', ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, '',
                            ?, ?, ?, '',
                            ?, ?, ?, ?,
                            'N', '', '', '', '',
                            '', ?, '', '', 'N'
                        )
                        """,
                        expoKey, lanNo, req.getHsCode(), req.getIvNo(),
                        totalWeight, defaultIfBlank(req.getWeightUnit(), "KG"),
                        qty, "U",
                        packageCnt, defaultIfBlank(req.getPackageUnit(), "OU"),
                        defaultIfBlank(req.getItemName(), ""),
                        defaultIfBlank(req.getItemName(), ""),
                        defaultIfBlank(req.getItemName(), ""),
                        gyeljeInput, gyeljeInput, totalWon, gyeljeInput,
                        defaultIfBlank(req.getOriginCountry(), "KR")
                );
                executeUpdate(conn, "UPDATE expo2 SET exlan_yogchk='' WHERE exlan_key=? AND exlan_lan=?", expoKey, lanNo);

                executeUpdate(conn, """
                        INSERT INTO expo3 (
                            Expum_key, Expum_lan, Expum_haeng, Expum_jepum_code,
                            Expum_pum, Expum_sungbun, Expum_su, Expum_su_danwi, Expum_jung, Expum_jung_danwi,
                            Expum_indojo, Expum_gyelje_money, Expum_gyelje_gum, Expum_danga, Expum_jung_cd,
                            Expum_pum_a, Expum_pum_b, Expum_pum_c, Expum_pum_d, Expum_pum_e, Expum_pum_f, Expum_pum_g, Expum_pum_h,
                            Expum_sungbun_a, Expum_sungbun_b,
                            Expum_pum1, Expum_pum2, Expum_pum3, Expum_pum4, Expum_pum5,
                            Expum_gk1, Expum_gk2, Expum_gk3, Expum_gk4, Expum_gk5,
                            Expum_chamjo_no
                        ) VALUES (
                            ?, ?, ?, '',
                            '', '', ?, 'UN', 0, '',
                            '', '', ?, ?, '',
                            ?, ?, ?, '', '', '', '', '',
                            '', '',
                            '', '', '', '', '',
                            '', '', '', '', '',
                            ''
                        )
                        """,
                        expoKey, lanNo, hangNo,
                        qty, gyeljeInput, gyeljeInput,
                        defaultIfBlank(req.getItemNameLine1(), defaultIfBlank(req.getItemName(), "")),
                        req.getContainerNo(),
                        defaultIfBlank(req.getItemNameLine3(), "")
                );

                executeUpdate(conn,
                        "INSERT INTO expcar (EXPO5_KEY, EXPO5_LAN, EXPO5_HNG, EXPO5_SEQNO, EXPO5_NO, EXPO5_JUNG_CD, JJGBN, DELFLAG) VALUES (?, ?, ?, ?, ?, '', '', '')",
                        expoKey, lanNo, hangNo, seqNo, req.getContainerNo());
                executeUpdate(conn,
                        "INSERT INTO excon (ExCon_Key, ExCon_Seq, ExCon_No) VALUES (?, ?, ?)",
                        expoKey, hangNo, req.getContainerNo());

                executeUpdate(conn,
                        "DELETE FROM expo3_ft WHERE ft_key LIKE CONCAT(?, '%')",
                        expoKey);
                executeUpdate(conn, """
                        INSERT INTO EXPO3_FT
                        SELECT CONCAT(EXPUM_KEY, EXPUM_LAN, EXPUM_HAENG),
                               CONCAT(EXPUM_PUM_A, EXPUM_PUM_B, EXPUM_PUM_C, EXPUM_PUM_D, EXPUM_PUM_E, EXPUM_PUM_F, EXPUM_PUM_G, EXPUM_PUM_H),
                               EXLAN_HS, EXLAN_WONSANJI, EXPO_SINGO_DATE, EXPO_TRUST_CODE, EXPUM_JEPUM_CODE, EXPUM_DANGA,
                               'Y',
                               CASE WHEN EXPO_SINGO_DATE >= '20160423' THEN EXPO_SINGO_NO
                                    ELSE CONCAT(EXPO_SEGWAN, EXPO_GWA, EXPO_SINGO_YEAR, EXPO_SINGO_NO, EXPO_SINGO_DG) END,
                               EXPUM_KEY, EXPUM_LAN, EXPUM_HAENG
                        FROM EXPO3, EXPO2, EXPO1
                        WHERE EXPO1.EXPO_KEY = EXPO2.EXLAN_KEY
                          AND EXPO2.EXLAN_KEY = EXPO3.EXPUM_KEY
                          AND EXPO2.EXLAN_LAN = EXPO3.EXPUM_LAN
                          AND EXPO1.EXPO_KEY = ?
                        """,
                        expoKey);

                executeUpdate(conn, "UPDATE expo1 SET userno='' WHERE expo_key=?", expoKey);
                conn.commit();

                return NcustomsContainerTempSaveResponse.builder()
                        .expoKey(expoKey)
                        .expoJechlNo(nextDno)
                        .lanNo(lanNo)
                        .hangNo(hangNo)
                        .containerNo(req.getContainerNo())
                        .addDtTime(addDtTime)
                        .build();
            } catch (CustomException e) {
                rollbackQuietly(conn);
                throw e;
            } catch (Exception e) {
                rollbackQuietly(conn);
                log.error("[NCustoms] failed to temp-save with container", e);
                throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "ncustoms temp save failed");
            } finally {
                if (lockAcquired) {
                    releaseLock(conn, lockKey);
                }
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("[NCustoms] failed to connect ncustoms db (createTempSaveWithContainer)", e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to connect ncustoms db");
        }
    }

    private boolean acquireLock(Connection conn, String lockKey, int timeoutSeconds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, lockKey);
            ps.setInt(2, timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private void releaseLock(Connection conn, String lockKey) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockKey);
            ps.executeQuery();
        } catch (Exception e) {
            log.warn("[NCustoms] lock release failed key={}", lockKey);
        }
    }

    private int executeUpdate(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private boolean exists(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int queryInt(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "query did not return value");
                }
                return rs.getInt(1);
            }
        }
    }

    private String queryRequiredString(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "required row not found");
                }
                String value = rs.getString(1);
                if (value == null || value.isBlank()) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, "required serial value is empty");
                }
                return value.trim();
            }
        }
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof BigDecimal v) {
                ps.setBigDecimal(i + 1, v);
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    private String incrementSerial(String current) {
        Matcher matcher = SERIAL_PATTERN.matcher(current);
        if (!matcher.matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "invalid serial format: " + current);
        }
        String prefix = matcher.group(1);
        String digits = matcher.group(2);
        long next = Long.parseLong(digits) + 1;
        return prefix + String.format("%0" + digits.length() + "d", next);
    }

    private String resolveSingoDate(String ignoredSingoDate) {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).format(YMD);
    }

    private String resolveWriterId(String requestWriterId, String actorLoginId) {
        return defaultIfBlank(actorLoginId, defaultIfBlank(requestWriterId, "SYSTEM"));
    }

    private String resolveWriterName(String requestWriterName, String actorLoginId) {
        return defaultIfBlank(actorLoginId, defaultIfBlank(requestWriterName, "SYSTEM"));
    }

    private DealMaster resolveDealMaster(Connection conn, String dealCode, String fallbackSangho, String fallbackTong, String fallbackSaup) throws SQLException {
        if (dealCode == null || dealCode.isBlank()) {
            return new DealMaster(defaultIfBlank(fallbackSangho, ""), defaultIfBlank(fallbackTong, ""), defaultIfBlank(fallbackSaup, ""));
        }

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT Deal_sangho, Deal_tong, Deal_saup
                FROM DDeal
                WHERE Deal_code=?
                """)) {
            ps.setString(1, dealCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new DealMaster(defaultIfBlank(fallbackSangho, ""), defaultIfBlank(fallbackTong, ""), defaultIfBlank(fallbackSaup, ""));
                }
                return new DealMaster(
                        defaultIfBlank(rs.getString("Deal_sangho"), defaultIfBlank(fallbackSangho, "")),
                        defaultIfBlank(rs.getString("Deal_tong"), defaultIfBlank(fallbackTong, "")),
                        defaultIfBlank(rs.getString("Deal_saup"), defaultIfBlank(fallbackSaup, ""))
                );
            }
        }
    }

    private String resolveGonggubSangho(Connection conn, String gonggubCode, String fallbackSangho) throws SQLException {
        if (gonggubCode == null || gonggubCode.isBlank()) {
            return defaultIfBlank(fallbackSangho, "");
        }

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT Gonggub_sangho
                FROM Dgonggub
                WHERE Gonggub_code=?
                """)) {
            ps.setString(1, gonggubCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return defaultIfBlank(fallbackSangho, "");
                }
                return defaultIfBlank(rs.getString("Gonggub_sangho"), defaultIfBlank(fallbackSangho, ""));
            }
        }
    }

    private String nextAvailablePno(Connection conn, String year, String userCode) throws SQLException {
        String current = queryRequiredString(
                conn,
                "SELECT pno_expo FROM pno WHERE pno_year=? AND pno_user=?",
                year, userCode
        );
        String candidate = incrementSerial(current);
        int retry = 0;
        while (exists(conn, "SELECT 1 FROM expo1 WHERE expo_key=?", year + userCode + candidate)) {
            if (retry++ >= SERIAL_MAX_RETRY) {
                throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to allocate pno serial");
            }
            candidate = incrementSerial(candidate);
        }

        int rows = executeUpdate(
                conn,
                "UPDATE pno SET pno_expo=? WHERE pno_year=? AND pno_user=?",
                candidate, year, userCode
        );
        if (rows != 1) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to update pno");
        }
        return candidate;
    }

    private String nextAvailableDno(Connection conn, String year, String userCode) throws SQLException {
        String current = queryRequiredString(
                conn,
                "SELECT no_expo FROM dno WHERE no_user=? AND no_year=?",
                userCode, year
        );
        String candidate = incrementSerial(current);
        int retry = 0;
        while (exists(conn, "SELECT 1 FROM expo1 WHERE expo_year=? AND expo_jechlno=?", year, candidate)) {
            if (retry++ >= SERIAL_MAX_RETRY) {
                throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to allocate dno serial");
            }
            candidate = incrementSerial(candidate);
        }

        int rows = executeUpdate(
                conn,
                "UPDATE dno SET no_expo=? WHERE no_user=? AND no_year=?",
                candidate, userCode, year
        );
        if (rows != 1) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, "failed to update dno");
        }
        return candidate;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (Exception ignored) {
        }
    }

    private record DealMaster(String sangho, String tong, String saup) {
    }
}
