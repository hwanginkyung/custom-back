#!/usr/bin/env python3
"""
Local NCustoms temp-save worker.

Flow:
1) claim pending jobs from cloud customs server
2) execute temp-save on local NCustoms DB
3) report success/failure back to cloud
"""

import argparse
import json
import os
import re
import signal
import socket
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
from typing import Any, Dict, List, Optional, Tuple

import mysql.connector
import requests
from requests import RequestException


STOP_REQUESTED = False
KST = timezone(timedelta(hours=9))
SERIAL_PATTERN = re.compile(r"^([A-Za-z]*)(\d+)$")
SERIAL_MAX_RETRY = 1000


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def env_int(name: str, default: int) -> int:
    raw = env(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be integer, got '{raw}'") from exc


def env_float(name: str, default: float) -> float:
    raw = env(name)
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be number, got '{raw}'") from exc


def env_bool(name: str, default: bool) -> bool:
    raw = env(name).lower()
    if not raw:
        return default
    if raw in {"1", "true", "yes", "y", "on"}:
        return True
    if raw in {"0", "false", "no", "n", "off"}:
        return False
    raise RuntimeError(f"{name} must be boolean, got '{raw}'")


def log(message: str) -> None:
    print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [temp-save] {message}", flush=True)


def install_signal_handlers() -> None:
    def handle_signal(sig, _frame):
        global STOP_REQUESTED
        STOP_REQUESTED = True
        log(f"stop requested by signal={sig}")

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)


@dataclass
class WorkerConfig:
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    db_charset: str
    db_collation: str
    api_base_url: str
    agent_token: str
    company_id: int
    worker_id: str
    mode: str
    interval_seconds: int
    claim_limit: int
    connect_timeout_seconds: float
    http_timeout_seconds: float
    http_verify_ssl: bool
    retry_max: int
    retry_backoff_seconds: float

    @classmethod
    def from_env(cls) -> "WorkerConfig":
        default_worker = f"{socket.gethostname()}-ncustoms-worker"
        return cls(
            db_host=env("LOCAL_DB_HOST", "127.0.0.1"),
            db_port=env_int("LOCAL_DB_PORT", 3306),
            db_name=env("LOCAL_DB_NAME", "ncustoms"),
            db_user=env("LOCAL_DB_USER", "kcba"),
            db_password=env("LOCAL_DB_PASSWORD", ""),
            db_charset=env("LOCAL_DB_CHARSET", "euckr"),
            db_collation=env("LOCAL_DB_COLLATION", ""),
            api_base_url=env("TEMP_SAVE_API_BASE_URL", "http://43.203.237.154:8080/api/ncustoms/temp-save/jobs"),
            agent_token=env("NCUSTOMS_TEMP_SAVE_AGENT_TOKEN", env("CLIENT_SYNC_AGENT_TOKEN", "")),
            company_id=env_int("TEMP_SAVE_COMPANY_ID", env_int("SYNC_COMPANY_ID", 0)),
            worker_id=env("TEMP_SAVE_WORKER_ID", default_worker),
            mode=env("TEMP_SAVE_MODE", "once").lower(),
            interval_seconds=env_int("TEMP_SAVE_INTERVAL_SECONDS", 10),
            claim_limit=env_int("TEMP_SAVE_CLAIM_LIMIT", 1),
            connect_timeout_seconds=env_float("TEMP_SAVE_CONNECT_TIMEOUT_SECONDS", 5.0),
            http_timeout_seconds=env_float("TEMP_SAVE_HTTP_TIMEOUT_SECONDS", 20.0),
            http_verify_ssl=env_bool("TEMP_SAVE_HTTP_VERIFY_SSL", True),
            retry_max=env_int("TEMP_SAVE_RETRY_MAX", 3),
            retry_backoff_seconds=env_float("TEMP_SAVE_RETRY_BACKOFF_SECONDS", 2.0),
        )

    def validate(self) -> None:
        if not self.api_base_url:
            raise RuntimeError("TEMP_SAVE_API_BASE_URL is required")
        if not self.agent_token:
            raise RuntimeError("NCUSTOMS_TEMP_SAVE_AGENT_TOKEN or CLIENT_SYNC_AGENT_TOKEN is required")
        if self.company_id <= 0:
            raise RuntimeError("TEMP_SAVE_COMPANY_ID must be > 0")
        if self.mode not in {"once", "daemon"}:
            raise RuntimeError("TEMP_SAVE_MODE must be one of: once, daemon")
        if self.interval_seconds <= 0:
            raise RuntimeError("TEMP_SAVE_INTERVAL_SECONDS must be > 0")
        if self.claim_limit <= 0:
            raise RuntimeError("TEMP_SAVE_CLAIM_LIMIT must be > 0")
        if self.retry_max <= 0:
            raise RuntimeError("TEMP_SAVE_RETRY_MAX must be > 0")


def now_yyyymmdd_hhmmss() -> str:
    return datetime.now(KST).strftime("%Y%m%d%H%M%S")


def now_yyyymmdd() -> str:
    return datetime.now(KST).strftime("%Y%m%d")


def trim(value: Any, default: str = "") -> str:
    if value is None:
        return default
    text = str(value).strip()
    return text if text else default


def decimal_or(value: Any, default: Decimal) -> Decimal:
    if value is None or (isinstance(value, str) and not value.strip()):
        return default
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return default


def increment_serial(current: str) -> str:
    m = SERIAL_PATTERN.match(current)
    if not m:
        raise RuntimeError(f"invalid serial format: {current}")
    prefix, digits = m.group(1), m.group(2)
    next_value = int(digits) + 1
    return f"{prefix}{next_value:0{len(digits)}d}"


def execute_update(cur, sql: str, params: Tuple = ()) -> int:
    cur.execute(sql, params)
    return cur.rowcount


def query_one(cur, sql: str, params: Tuple = ()) -> Optional[Tuple]:
    cur.execute(sql, params)
    return cur.fetchone()


def exists(cur, sql: str, params: Tuple = ()) -> bool:
    row = query_one(cur, sql, params)
    return row is not None


def query_required_string(cur, sql: str, params: Tuple = ()) -> str:
    row = query_one(cur, sql, params)
    if not row:
        raise RuntimeError("required row not found")
    value = trim(row[0], "")
    if not value:
        raise RuntimeError("required serial value is empty")
    return value


def resolve_deal_master(cur, deal_code: str, fallback_sangho: str, fallback_tong: str, fallback_saup: str) -> Tuple[str, str, str]:
    code = trim(deal_code, "")
    if not code:
        return trim(fallback_sangho, ""), trim(fallback_tong, ""), trim(fallback_saup, "")
    row = query_one(
        cur,
        "SELECT Deal_sangho, Deal_tong, Deal_saup FROM DDeal WHERE Deal_code=%s",
        (code,),
    )
    if not row:
        return trim(fallback_sangho, ""), trim(fallback_tong, ""), trim(fallback_saup, "")
    return trim(row[0], trim(fallback_sangho, "")), trim(row[1], trim(fallback_tong, "")), trim(row[2], trim(fallback_saup, ""))


def resolve_deal_master_detail(cur, deal_code: str, fallback: Optional[Dict[str, Any]] = None) -> Dict[str, str]:
    base = fallback or {}
    result = {
        "sangho": trim(base.get("sangho"), ""),
        "tong": trim(base.get("tong"), ""),
        "saup": trim(base.get("saup"), ""),
        "post": trim(base.get("post"), ""),
        "juso": trim(base.get("juso"), ""),
        "juso2": trim(base.get("juso2"), ""),
        "road_nm_cd": trim(base.get("road_nm_cd"), ""),
        "buld_mng_no": trim(base.get("buld_mng_no"), ""),
        "name": trim(base.get("name"), ""),
    }
    code = trim(deal_code, "")
    if not code:
        return result
    row = query_one(
        cur,
        """
        SELECT Deal_sangho, Deal_tong, Deal_saup, Deal_post, deal_juso, Deal_juso2, ROAD_NM_CD, BULD_MNG_NO, Deal_name
        FROM DDeal
        WHERE Deal_code=%s
        """,
        (code,),
    )
    if not row:
        return result
    return {
        "sangho": trim(row[0], result["sangho"]),
        "tong": trim(row[1], result["tong"]),
        "saup": trim(row[2], result["saup"]),
        "post": trim(row[3], result["post"]),
        "juso": trim(row[4], result["juso"]),
        "juso2": trim(row[5], result["juso2"]),
        "road_nm_cd": trim(row[6], result["road_nm_cd"]),
        "buld_mng_no": trim(row[7], result["buld_mng_no"]),
        "name": trim(row[8], result["name"]),
    }


def resolve_gonggub_sangho(cur, gonggub_code: str, fallback_sangho: str) -> str:
    code = trim(gonggub_code, "")
    if not code:
        return trim(fallback_sangho, "")
    row = query_one(cur, "SELECT Gonggub_sangho FROM Dgonggub WHERE Gonggub_code=%s", (code,))
    if not row:
        return trim(fallback_sangho, "")
    return trim(row[0], trim(fallback_sangho, ""))


def next_available_pno(cur, year: str, user_code: str) -> str:
    current = query_required_string(cur, "SELECT pno_expo FROM pno WHERE pno_year=%s AND pno_user=%s", (year, user_code))
    candidate = increment_serial(current)
    retry = 0
    while exists(cur, "SELECT 1 FROM expo1 WHERE expo_key=%s", (year + user_code + candidate,)):
        retry += 1
        if retry >= SERIAL_MAX_RETRY:
            raise RuntimeError("failed to allocate pno serial")
        candidate = increment_serial(candidate)
    rows = execute_update(cur, "UPDATE pno SET pno_expo=%s WHERE pno_year=%s AND pno_user=%s", (candidate, year, user_code))
    if rows != 1:
        raise RuntimeError("failed to update pno")
    return candidate


def next_available_dno(cur, year: str, user_code: str) -> str:
    current = query_required_string(cur, "SELECT no_expo FROM dno WHERE no_user=%s AND no_year=%s", (user_code, year))
    candidate = increment_serial(current)
    retry = 0
    while exists(cur, "SELECT 1 FROM expo1 WHERE expo_year=%s AND expo_jechlno=%s", (year, candidate)):
        retry += 1
        if retry >= SERIAL_MAX_RETRY:
            raise RuntimeError("failed to allocate dno serial")
        candidate = increment_serial(candidate)
    rows = execute_update(cur, "UPDATE dno SET no_expo=%s WHERE no_user=%s AND no_year=%s", (candidate, user_code, year))
    if rows != 1:
        raise RuntimeError("failed to update dno")
    return candidate


def db_connect(cfg: WorkerConfig):
    conn = mysql.connector.connect(
        host=cfg.db_host,
        port=cfg.db_port,
        database=cfg.db_name,
        user=cfg.db_user,
        password=cfg.db_password,
        charset=cfg.db_charset,
        use_unicode=True,
        autocommit=False,
        connection_timeout=max(1, int(cfg.connect_timeout_seconds)),
    )
    if hasattr(conn, "set_charset_collation"):
        if cfg.db_collation:
            conn.set_charset_collation(charset=cfg.db_charset, collation=cfg.db_collation)
        else:
            conn.set_charset_collation(charset=cfg.db_charset)
    return conn


def execute_temp_save_local(cfg: WorkerConfig, payload: Dict[str, Any]) -> Dict[str, str]:
    year = trim(payload.get("year"))
    if len(year) != 4 or not year.isdigit():
        raise RuntimeError("year must be 4 digits")

    user_code = trim(payload.get("userCode"), "4")
    segwan = trim(payload.get("segwan"))
    gwa = trim(payload.get("gwa"))
    suchulja_code = trim(payload.get("suchuljaCode"))
    trust_code = trim(payload.get("trustCode"))
    iv_no = trim(payload.get("ivNo"))
    container_no = trim(payload.get("containerNo"))
    hs_code = trim(payload.get("hsCode"))
    if not segwan or not gwa or not suchulja_code or not trust_code or not iv_no or not container_no or not hs_code:
        raise RuntimeError("missing required fields for temp-save")

    writer_id = trim(payload.get("writerId"), "SYSTEM")
    writer_name = trim(payload.get("writerName"), writer_id)

    singo_date = now_yyyymmdd()
    add_dttm = now_yyyymmdd_hhmmss()

    usd_exch = decimal_or(payload.get("usdExch"), Decimal("0"))
    gyelje_input = decimal_or(payload.get("gyeljeInput"), Decimal("0"))
    total_won = (gyelje_input * usd_exch).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    qty = decimal_or(payload.get("qty"), Decimal("1"))
    total_weight = decimal_or(payload.get("totalWeight"), Decimal("0"))
    package_cnt = decimal_or(payload.get("packageCount"), Decimal("1"))

    lan_no = trim(payload.get("lanNo"), "001")
    hang_no = trim(payload.get("hangNo"), "01")
    seq_no = trim(payload.get("containerSeqNo"), "001")

    lock_key = f"ncustoms:expo:{year}:{user_code}"
    conn = db_connect(cfg)
    cur = conn.cursor()
    lock_acquired = False
    try:
        execute_update(cur, "SET NAMES euckr")
        cur.execute("SELECT GET_LOCK(%s, %s)", (lock_key, 10))
        lock_row = cur.fetchone()
        lock_acquired = bool(lock_row and int(lock_row[0]) == 1)
        if not lock_acquired:
            raise RuntimeError("failed to acquire ncustoms lock")

        next_pno = next_available_pno(cur, year, user_code)
        next_dno = next_available_dno(cur, year, user_code)
        expo_key = f"{year}{user_code}{next_pno}"

        suchulja_detail = resolve_deal_master_detail(
            cur,
            suchulja_code,
            {
                "sangho": payload.get("suchuljaSangho"),
                "post": payload.get("postCode"),
                "juso": payload.get("juso"),
                "juso2": payload.get("locationAddr"),
            },
        )
        whaju_detail = resolve_deal_master_detail(
            cur,
            trim(payload.get("whajuCode")),
            {
                "sangho": payload.get("whajuSangho"),
                "tong": payload.get("whajuTong"),
                "saup": payload.get("whajuSaup"),
            },
        )
        trust_detail = resolve_deal_master_detail(
            cur,
            trust_code,
            {
                "sangho": payload.get("trustSangho"),
                "name": payload.get("trustName"),
                "tong": payload.get("trustTong"),
                "saup": payload.get("trustSaup"),
                "post": payload.get("trustPost"),
                "juso": payload.get("trustJuso"),
                "juso2": payload.get("trustJuso2"),
                "road_nm_cd": payload.get("trustRoadCd"),
                "buld_mng_no": payload.get("trustBuildMngNo"),
            },
        )
        suchulja_sangho = suchulja_detail["sangho"]
        whaju_sangho = whaju_detail["sangho"]
        whaju_tong = whaju_detail["tong"]
        whaju_saup = whaju_detail["saup"]
        trust_sangho = trust_detail["sangho"]
        trust_tong = trust_detail["tong"]
        trust_saup = trust_detail["saup"]
        gumaeja_sangho = resolve_gonggub_sangho(cur, trim(payload.get("gumaejaCode")), trim(payload.get("gumaejaSangho")))
        item_name = trim(payload.get("itemName"))
        if not item_name:
            item_name = "USED CAR" if hs_code.startswith("8703") else "ITEM"
        item_name_line1 = trim(payload.get("itemNameLine1"), item_name)
        item_name_line3 = trim(payload.get("itemNameLine3"))

        execute_update(cur, "DELETE FROM expo2 WHERE exlan_key=%s", (expo_key,))
        execute_update(cur, "DELETE FROM expo3 WHERE expum_key=%s", (expo_key,))
        execute_update(cur, "DELETE FROM expcar WHERE expo5_key=%s", (expo_key,))
        execute_update(cur, "DELETE FROM excon WHERE excon_key=%s", (expo_key,))
        execute_update(cur, "DELETE FROM expo3_ft WHERE ft_key LIKE CONCAT(%s, '%')", (expo_key,))
        execute_update(cur, "DELETE FROM expo1 WHERE expo_key=%s", (expo_key,))

        execute_update(
            cur,
            """
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
                %s, %s, %s,
                'X', 'N', '',
                %s, %s, %s, %s, %s,
                %s,
                %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s,
                'A', 'TT',
                %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s,
                %s, %s, %s, %s, 'O',
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s,
                %s, %s, %s, '', ''
            )
            """,
            (
                expo_key, year, next_dno,
                year[2:], segwan, gwa, singo_date, trim(payload.get("singoGbn"), "B"),
                suchulja_sangho,
                suchulja_code, suchulja_sangho, trim(payload.get("suchuljaGbn"), "C"),
                trim(payload.get("whajuCode")), whaju_sangho, whaju_tong, whaju_saup,
                trim(payload.get("gumaejaCode")), gumaeja_sangho,
                trim(payload.get("mokjukCode"), "KG"), trim(payload.get("mokjukName"), "KYRGY"),
                trim(payload.get("hangguCode"), "KRINC"), trim(payload.get("hangguName"), ""),
                trim(payload.get("unsongType"), "10"), trim(payload.get("unsongBox"), "LC"), singo_date,
                trim(payload.get("postCode"), suchulja_detail["post"]),
                trim(payload.get("juso"), suchulja_detail["juso"]),
                trim(payload.get("locationAddr"), suchulja_detail["juso2"]),
                iv_no,
                lan_no,
                total_weight, trim(payload.get("weightUnit"), "KG"), package_cnt,
                total_won, usd_exch, gyelje_input,
                trim(payload.get("indojo"), "FOB"), trim(payload.get("gyeljeMoney"), "USD"), usd_exch, gyelje_input,
                trust_code,
                trust_sangho,
                trim(payload.get("trustJuso"), trust_detail["juso"]),
                trim(payload.get("trustName"), trust_detail["name"]),
                trust_tong,
                trust_saup,
                trim(payload.get("trustPost"), trust_detail["post"]),
                trim(payload.get("trustJuso2"), trust_detail["juso2"]),
                trim(payload.get("trustRoadCd"), trust_detail["road_nm_cd"]),
                trim(payload.get("trustBuildMngNo"), trust_detail["buld_mng_no"]),
                trim(payload.get("eventType"), "A"), trim(payload.get("southNorthTradeYn"), "Y"),
                writer_id, writer_name, add_dttm
            ),
        )

        row = query_one(cur, "SELECT COALESCE(MAX(expo_cnt), 0) + 1 FROM expodamdang WHERE expo_key=%s", (expo_key,))
        next_expo_cnt = int(row[0]) if row else 1
        execute_update(
            cur,
            "INSERT INTO expodamdang (expo_key, expo_cnt, writter_id, writter, write_dttm, procgbn) VALUES (%s, %s, %s, %s, %s, %s)",
            (expo_key, next_expo_cnt, writer_id, writer_name, add_dttm, trim(payload.get("procGbn"), "AUTO")),
        )

        execute_update(
            cur,
            """
            INSERT INTO expo2 (
                Exlan_key, Exlan_lan, Exlan_jung_gubun, Exlan_hs, Exlan_jepum_code,
                Exlan_jung, Exlan_jung_danwi, Exlan_su, Exlan_su_danwi,
                Exlan_pojang_su, Exlan_pojang_danwi, Exlan_whan_jepum,
                Exlan_ename, Exlan_egukyk, Exlan_pum1, Exlan_gukyk,
                Exlan_gyelje_gum, Exlan_gyelje_fob, Exlan_fob_won, Exlan_fob_usd,
                Exlan_attach_yn, EXLAN_CONT_AUTOLOAD, EXLAN_AGREE_CD, EXLAN_KOSAGBN, EXLAN_KOSAAPPNO,
                exlan_sangpyo, exlan_wonsanji, exlan_wonsanji_bang, exlan_wonsanji_pyosi, exlan_CoIssueYN
            ) VALUES (
                %s, %s, '', %s, %s,
                %s, %s, %s, %s,
                %s, %s, '',
                %s, %s, %s, '',
                %s, %s, %s, %s,
                'N', '', '', '', '',
                '', %s, '', '', 'N'
            )
            """,
            (
                expo_key, lan_no, hs_code, iv_no,
                total_weight, trim(payload.get("weightUnit"), "KG"),
                qty, "U",
                package_cnt, trim(payload.get("packageUnit"), "OU"),
                item_name,
                item_name,
                item_name,
                gyelje_input, gyelje_input, total_won, gyelje_input,
                trim(payload.get("originCountry"), "KR"),
            ),
        )
        execute_update(cur, "UPDATE expo2 SET exlan_yogchk='' WHERE exlan_key=%s AND exlan_lan=%s", (expo_key, lan_no))

        execute_update(
            cur,
            """
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
                %s, %s, %s, '',
                '', '', %s, 'UN', 0, '',
                '', '', %s, %s, '',
                %s, %s, %s, '', '', '', '', '',
                '', '',
                '', '', '', '', '',
                '', '', '', '', '',
                ''
            )
            """,
            (
                expo_key, lan_no, hang_no,
                qty, gyelje_input, gyelje_input,
                item_name_line1,
                container_no,
                item_name_line3,
            ),
        )

        execute_update(
            cur,
            "INSERT INTO expcar (EXPO5_KEY, EXPO5_LAN, EXPO5_HNG, EXPO5_SEQNO, EXPO5_NO, EXPO5_JUNG_CD, JJGBN, DELFLAG) VALUES (%s, %s, %s, %s, %s, '', '', '')",
            (expo_key, lan_no, hang_no, seq_no, container_no),
        )
        execute_update(
            cur,
            "INSERT INTO excon (ExCon_Key, ExCon_Seq, ExCon_No) VALUES (%s, %s, %s)",
            (expo_key, hang_no, container_no),
        )

        execute_update(cur, "DELETE FROM expo3_ft WHERE ft_key LIKE CONCAT(%s, '%')", (expo_key,))
        execute_update(
            cur,
            """
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
              AND EXPO1.EXPO_KEY = %s
            """,
            (expo_key,),
        )
        execute_update(cur, "UPDATE expo1 SET userno='' WHERE expo_key=%s", (expo_key,))

        conn.commit()
        return {
            "expoKey": expo_key,
            "expoJechlNo": next_dno,
            "lanNo": lan_no,
            "hangNo": hang_no,
            "containerNo": container_no,
            "addDtTime": add_dttm,
        }
    except Exception:
        conn.rollback()
        raise
    finally:
        if lock_acquired:
            try:
                cur.execute("SELECT RELEASE_LOCK(%s)", (lock_key,))
                cur.fetchone()
            except Exception:
                pass
        cur.close()
        conn.close()


def claim_jobs(cfg: WorkerConfig) -> List[Dict[str, Any]]:
    payload = {
        "companyId": cfg.company_id,
        "limit": cfg.claim_limit,
        "workerId": cfg.worker_id,
    }
    url = cfg.api_base_url.rstrip("/") + "/claim"
    timeout = (cfg.connect_timeout_seconds, cfg.http_timeout_seconds)
    response = requests.post(
        url,
        headers={
            "Content-Type": "application/json",
            "X-Agent-Token": cfg.agent_token,
        },
        json=payload,
        timeout=timeout,
        verify=cfg.http_verify_ssl,
    )
    response.raise_for_status()
    body = response.json()
    jobs = body.get("jobs", [])
    return jobs if isinstance(jobs, list) else []


def complete_job(cfg: WorkerConfig, job_id: int, success: bool, result: Optional[Dict[str, Any]], error_message: Optional[str]) -> None:
    payload = {
        "success": success,
        "workerId": cfg.worker_id,
        "result": result,
        "errorMessage": error_message,
    }
    url = f"{cfg.api_base_url.rstrip('/')}/{job_id}/complete"
    timeout = (cfg.connect_timeout_seconds, cfg.http_timeout_seconds)
    last_error = None
    for attempt in range(1, cfg.retry_max + 1):
        try:
            response = requests.post(
                url,
                headers={
                    "Content-Type": "application/json",
                    "X-Agent-Token": cfg.agent_token,
                },
                json=payload,
                timeout=timeout,
                verify=cfg.http_verify_ssl,
            )
            if 500 <= response.status_code < 600 and attempt < cfg.retry_max:
                raise RequestException(f"server {response.status_code}: {response.text}")
            response.raise_for_status()
            return
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt < cfg.retry_max:
                wait_seconds = cfg.retry_backoff_seconds * attempt
                log(f"complete attempt {attempt}/{cfg.retry_max} failed: {exc}; retry in {wait_seconds:.1f}s")
                time.sleep(wait_seconds)
                continue
            break
    raise RuntimeError(f"failed to complete job={job_id}: {last_error}")


def process_one_job(cfg: WorkerConfig, job: Dict[str, Any]) -> None:
    job_id = int(job.get("jobId"))
    req = job.get("tempSaveRequest")
    if not isinstance(req, dict):
        complete_job(cfg, job_id, False, None, "invalid tempSaveRequest payload")
        return
    try:
        result = execute_temp_save_local(cfg, req)
        complete_job(cfg, job_id, True, result, None)
        log(f"job={job_id} succeeded expoKey={result.get('expoKey')}")
    except Exception as exc:  # noqa: BLE001
        complete_job(cfg, job_id, False, None, str(exc))
        log(f"job={job_id} failed: {exc}")


def run_once(cfg: WorkerConfig) -> int:
    try:
        jobs = claim_jobs(cfg)
    except Exception as exc:  # noqa: BLE001
        log(f"claim failed: {exc}")
        return 1

    if not jobs:
        log("no pending temp-save jobs")
        return 0

    log(f"claimed jobs={len(jobs)}")
    for job in jobs:
        if STOP_REQUESTED:
            break
        process_one_job(cfg, job)
    return 0


def run_daemon(cfg: WorkerConfig) -> int:
    log(f"daemon mode started interval={cfg.interval_seconds}s")
    while not STOP_REQUESTED:
        run_once(cfg)
        for _ in range(cfg.interval_seconds):
            if STOP_REQUESTED:
                break
            time.sleep(1)
    log("daemon mode stopped")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Local NCustoms temp-save job worker")
    parser.add_argument("--mode", choices=["once", "daemon"], default=None, help="override TEMP_SAVE_MODE")
    parser.add_argument("--interval-seconds", type=int, default=None, help="override TEMP_SAVE_INTERVAL_SECONDS")
    parser.add_argument("--print-config", action="store_true", help="print effective config and exit")
    return parser.parse_args()


def main() -> int:
    install_signal_handlers()
    args = parse_args()

    try:
        cfg = WorkerConfig.from_env()
        if args.mode:
            cfg.mode = args.mode
        if args.interval_seconds is not None:
            cfg.interval_seconds = args.interval_seconds
        cfg.validate()
    except Exception as exc:  # noqa: BLE001
        log(f"invalid config: {exc}")
        return 2

    if args.print_config:
        safe = {
            "db_host": cfg.db_host,
            "db_port": cfg.db_port,
            "db_name": cfg.db_name,
            "db_user": cfg.db_user,
            "db_charset": cfg.db_charset,
            "db_collation": cfg.db_collation or None,
            "api_base_url": cfg.api_base_url,
            "company_id": cfg.company_id,
            "worker_id": cfg.worker_id,
            "mode": cfg.mode,
            "interval_seconds": cfg.interval_seconds,
            "claim_limit": cfg.claim_limit,
            "connect_timeout_seconds": cfg.connect_timeout_seconds,
            "http_timeout_seconds": cfg.http_timeout_seconds,
            "http_verify_ssl": cfg.http_verify_ssl,
            "retry_max": cfg.retry_max,
            "retry_backoff_seconds": cfg.retry_backoff_seconds,
            "agent_token_set": bool(cfg.agent_token),
        }
        print(json.dumps(safe, ensure_ascii=False, indent=2))
        return 0

    if cfg.mode == "daemon":
        return run_daemon(cfg)
    return run_once(cfg)


if __name__ == "__main__":
    raise SystemExit(main())
