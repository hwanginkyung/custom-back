#!/usr/bin/env python3
"""
Local DDeal -> Cloud clients sync agent (push model).

Supports:
- once mode: run one incremental sync pass and exit
- daemon mode: keep running and sync every interval
- input mode db: read from local DDeal table
- input mode mock: read from local JSON fixture file
"""

import argparse
import json
import os
import signal
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Tuple

import mysql.connector
import requests
from requests import RequestException


STOP_REQUESTED = False
SCRIPT_DIR = Path(__file__).resolve().parent


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


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def log(message: str) -> None:
    print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [sync] {message}", flush=True)


def install_signal_handlers() -> None:
    def handle_signal(sig, _frame):
        global STOP_REQUESTED
        STOP_REQUESTED = True
        log(f"stop requested by signal={sig}")

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)


@dataclass
class AgentConfig:
    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str
    db_charset: str
    db_collation: str
    api_url: str
    agent_token: str
    company_id: int
    source_name: str
    code_prefix: str
    batch_size: int
    checkpoint_file: Path
    status_file: Path
    mode: str
    interval_seconds: int
    connect_timeout_seconds: float
    http_timeout_seconds: float
    http_verify_ssl: bool
    retry_max: int
    retry_backoff_seconds: float
    input_mode: str
    fixture_file: Path

    @classmethod
    def from_env(cls) -> "AgentConfig":
        checkpoint_default = SCRIPT_DIR / ".client-sync-checkpoint.json"
        status_default = SCRIPT_DIR / ".client-sync-status.json"
        fixture_default = SCRIPT_DIR / "fixtures" / "ddeal_sample.json"

        checkpoint_raw = env("SYNC_CHECKPOINT_FILE")
        status_raw = env("SYNC_STATUS_FILE")
        fixture_raw = env("SYNC_FIXTURE_FILE")

        checkpoint_file = Path(checkpoint_raw) if checkpoint_raw else checkpoint_default
        status_file = Path(status_raw) if status_raw else status_default
        fixture_file = Path(fixture_raw) if fixture_raw else fixture_default

        return cls(
            db_host=env("LOCAL_DB_HOST", "127.0.0.1"),
            db_port=env_int("LOCAL_DB_PORT", 3306),
            db_name=env("LOCAL_DB_NAME", "ncustoms"),
            db_user=env("LOCAL_DB_USER", "kcba"),
            db_password=env("LOCAL_DB_PASSWORD", ""),
            db_charset=env("LOCAL_DB_CHARSET", "euckr"),
            db_collation=env("LOCAL_DB_COLLATION", ""),
            api_url=env("SYNC_API_URL"),
            agent_token=env("CLIENT_SYNC_AGENT_TOKEN"),
            company_id=env_int("SYNC_COMPANY_ID", 0),
            source_name=env("SYNC_SOURCE", "local-ddeal-agent"),
            code_prefix=env("SYNC_CODE_PREFIX", "00"),
            batch_size=env_int("SYNC_BATCH_SIZE", 200),
            checkpoint_file=checkpoint_file,
            status_file=status_file,
            mode=env("SYNC_MODE", "once").lower(),
            interval_seconds=env_int("SYNC_INTERVAL_SECONDS", 300),
            connect_timeout_seconds=env_float("SYNC_CONNECT_TIMEOUT_SECONDS", 5.0),
            http_timeout_seconds=env_float("SYNC_HTTP_TIMEOUT_SECONDS", 20.0),
            http_verify_ssl=env_bool("SYNC_HTTP_VERIFY_SSL", True),
            retry_max=env_int("SYNC_RETRY_MAX", 3),
            retry_backoff_seconds=env_float("SYNC_RETRY_BACKOFF_SECONDS", 2.0),
            input_mode=env("SYNC_INPUT_MODE", "db").lower(),
            fixture_file=fixture_file,
        )

    def validate(self) -> None:
        if not self.api_url:
            raise RuntimeError("SYNC_API_URL is required")
        if not self.agent_token:
            raise RuntimeError("CLIENT_SYNC_AGENT_TOKEN is required")
        if self.company_id <= 0:
            raise RuntimeError("SYNC_COMPANY_ID must be > 0")
        if self.batch_size <= 0:
            raise RuntimeError("SYNC_BATCH_SIZE must be > 0")
        if self.mode not in {"once", "daemon"}:
            raise RuntimeError("SYNC_MODE must be one of: once, daemon")
        if self.interval_seconds <= 0:
            raise RuntimeError("SYNC_INTERVAL_SECONDS must be > 0")
        if self.retry_max <= 0:
            raise RuntimeError("SYNC_RETRY_MAX must be > 0")
        if self.input_mode not in {"db", "mock"}:
            raise RuntimeError("SYNC_INPUT_MODE must be one of: db, mock")
        if self.input_mode == "mock" and not self.fixture_file.exists():
            raise RuntimeError(f"SYNC_FIXTURE_FILE not found: {self.fixture_file}")


def load_checkpoint(path: Path) -> Tuple[str, str]:
    if not path.exists():
        return "", ""
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        log(f"checkpoint parse failed ({path}): {exc}; ignore and continue from start")
        return "", ""
    return str(payload.get("last_edit", "")).strip(), str(payload.get("last_code", "")).strip()


def save_checkpoint(path: Path, last_edit: str, last_code: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(
        json.dumps({"last_edit": last_edit, "last_code": last_code}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    tmp.replace(path)


def save_status(path: Path, payload: Dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def db_connect(cfg: AgentConfig):
    conn = mysql.connector.connect(
        host=cfg.db_host,
        port=cfg.db_port,
        database=cfg.db_name,
        user=cfg.db_user,
        password=cfg.db_password,
        charset=cfg.db_charset,
        use_unicode=True,
        autocommit=True,
        connection_timeout=max(1, int(cfg.connect_timeout_seconds)),
    )
    # Force connection charset/collation explicitly to avoid mojibake.
    if hasattr(conn, "set_charset_collation"):
        if cfg.db_collation:
            conn.set_charset_collation(charset=cfg.db_charset, collation=cfg.db_collation)
        else:
            conn.set_charset_collation(charset=cfg.db_charset)
    else:
        with conn.cursor() as cur:
            if cfg.db_collation:
                cur.execute(f"SET NAMES {cfg.db_charset} COLLATE {cfg.db_collation}")
            else:
                cur.execute(f"SET NAMES {cfg.db_charset}")
    return conn


def query_batch(conn, cfg: AgentConfig, last_edit: str, last_code: str) -> List[Dict]:
    sql = """
    SELECT deal_code, Deal_saupgbn, Deal_saup, Deal_sangho, Deal_name,
           Deal_post, deal_juso, Deal_juso2,
           Deal_tel, Deal_fax, Deal_tong,
           ROAD_NM_CD, BULD_MNG_NO,
           AddDtTime, EditDtTime
    FROM DDeal
    WHERE deal_code LIKE %(prefix)s
      AND (
        %(last_edit)s = ''
        OR EditDtTime > %(last_edit)s
        OR (EditDtTime = %(last_edit)s AND deal_code > %(last_code)s)
      )
    ORDER BY EditDtTime, deal_code
    LIMIT %(batch)s
    """
    with conn.cursor(dictionary=True) as cur:
        cur.execute(
            sql,
            {
                "prefix": f"{cfg.code_prefix}%",
                "last_edit": last_edit,
                "last_code": last_code,
                "batch": cfg.batch_size,
            },
        )
        return cur.fetchall()


def query_probe_rows(conn, cfg: AgentConfig, limit: int = 20) -> List[Dict]:
    sql = """
    SELECT deal_code, Deal_sangho, Deal_name, EditDtTime
    FROM DDeal
    WHERE deal_code LIKE %(prefix)s
    ORDER BY EditDtTime DESC, deal_code DESC
    LIMIT %(limit)s
    """
    with conn.cursor(dictionary=True) as cur:
        cur.execute(
            sql,
            {
                "prefix": f"{cfg.code_prefix}%",
                "limit": limit,
            },
        )
        return cur.fetchall()


def row_to_payload(row: Dict) -> Dict:
    def val(key: str) -> str:
        value = row.get(key)
        return "" if value is None else str(value)

    return {
        "dealCode": val("deal_code"),
        "dealSaupgbn": val("Deal_saupgbn"),
        "dealSaup": val("Deal_saup"),
        "dealSangho": val("Deal_sangho"),
        "dealName": val("Deal_name"),
        "dealPost": val("Deal_post"),
        "dealJuso": val("deal_juso"),
        "dealJuso2": val("Deal_juso2"),
        "dealTel": val("Deal_tel"),
        "dealFax": val("Deal_fax"),
        "dealTong": val("Deal_tong"),
        "roadNmCd": val("ROAD_NM_CD"),
        "buldMngNo": val("BULD_MNG_NO"),
        "addDtTime": val("AddDtTime"),
        "editDtTime": val("EditDtTime"),
    }


def normalize_fixture_item(item: Dict) -> Dict:
    # Accepts either DDeal-style row keys or already-normalized payload keys.
    if "dealCode" in item:
        keys = [
            "dealCode",
            "dealSaupgbn",
            "dealSaup",
            "dealSangho",
            "dealName",
            "dealPost",
            "dealJuso",
            "dealJuso2",
            "dealTel",
            "dealFax",
            "dealTong",
            "roadNmCd",
            "buldMngNo",
            "addDtTime",
            "editDtTime",
        ]
        return {k: "" if item.get(k) is None else str(item.get(k)) for k in keys}
    return row_to_payload(item)


def load_fixture_items(cfg: AgentConfig, last_edit: str, last_code: str) -> List[Dict]:
    try:
        raw = json.loads(cfg.fixture_file.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(f"fixture parse failed ({cfg.fixture_file}): {exc}") from exc

    if isinstance(raw, dict):
        source_items = raw.get("items", [])
    elif isinstance(raw, list):
        source_items = raw
    else:
        raise RuntimeError("fixture root must be array or object with 'items'")

    if not isinstance(source_items, list):
        raise RuntimeError("fixture 'items' must be array")

    items = [normalize_fixture_item(item) for item in source_items if isinstance(item, dict)]
    items.sort(key=lambda x: (x.get("editDtTime", ""), x.get("dealCode", "")))

    if not last_edit:
        return items

    filtered = []
    for item in items:
        edit = item.get("editDtTime", "")
        code = item.get("dealCode", "")
        if edit > last_edit or (edit == last_edit and code > last_code):
            filtered.append(item)
    return filtered


def push_batch(cfg: AgentConfig, items: List[Dict], checkpoint: str) -> Dict:
    payload = {
        "companyId": cfg.company_id,
        "source": cfg.source_name,
        "checkpoint": checkpoint,
        "items": items,
    }

    timeout = (cfg.connect_timeout_seconds, cfg.http_timeout_seconds)
    last_error = None
    for attempt in range(1, cfg.retry_max + 1):
        try:
            response = requests.post(
                cfg.api_url,
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
            data = response.json()
            if not isinstance(data, dict):
                raise RuntimeError("sync response is not JSON object")
            return data
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt < cfg.retry_max:
                wait_seconds = cfg.retry_backoff_seconds * attempt
                log(f"push attempt {attempt}/{cfg.retry_max} failed: {exc}; retry in {wait_seconds:.1f}s")
                time.sleep(wait_seconds)
            else:
                break

    raise RuntimeError(f"push failed after {cfg.retry_max} attempts: {last_error}")


def run_once_db(cfg: AgentConfig) -> int:
    started_at = utc_now_iso()
    last_edit, last_code = load_checkpoint(cfg.checkpoint_file)
    total_sent = 0
    total_created = 0
    total_updated = 0
    total_skipped = 0

    try:
        conn = db_connect(cfg)
    except Exception as exc:  # noqa: BLE001
        log(f"db connect failed: {exc}")
        save_status(
            cfg.status_file,
            {
                "startedAt": started_at,
                "finishedAt": utc_now_iso(),
                "success": False,
                "error": str(exc),
                "mode": cfg.mode,
            },
        )
        return 1

    try:
        while not STOP_REQUESTED:
            batch = query_batch(conn, cfg, last_edit, last_code)
            if not batch:
                log(f"done total={total_sent}, checkpoint=({last_edit}, {last_code})")
                save_status(
                    cfg.status_file,
                    {
                        "startedAt": started_at,
                        "finishedAt": utc_now_iso(),
                        "success": True,
                        "mode": cfg.mode,
                        "totalSent": total_sent,
                        "created": total_created,
                        "updated": total_updated,
                        "skipped": total_skipped,
                        "checkpoint": {
                            "last_edit": last_edit,
                            "last_code": last_code,
                        },
                    },
                )
                return 0

            checkpoint = f"{last_edit}|{last_code}" if last_edit else ""
            payload_items = [row_to_payload(row) for row in batch]
            result = push_batch(cfg, payload_items, checkpoint)

            total_sent += len(batch)
            total_created += int(result.get("created", 0) or 0)
            total_updated += int(result.get("updated", 0) or 0)
            total_skipped += int(result.get("skipped", 0) or 0)

            tail = batch[-1]
            last_edit = "" if tail.get("EditDtTime") is None else str(tail["EditDtTime"])
            last_code = "" if tail.get("deal_code") is None else str(tail["deal_code"])
            save_checkpoint(cfg.checkpoint_file, last_edit, last_code)

            log(
                "pushed={} total={} server(created={}, updated={}, skipped={}) checkpoint=({}, {})".format(
                    len(batch),
                    total_sent,
                    result.get("created"),
                    result.get("updated"),
                    result.get("skipped"),
                    last_edit,
                    last_code,
                )
            )

        log("stopped before batch completion")
        return 130
    except Exception as exc:  # noqa: BLE001
        log(f"sync failed: {exc}")
        save_status(
            cfg.status_file,
            {
                "startedAt": started_at,
                "finishedAt": utc_now_iso(),
                "success": False,
                "error": str(exc),
                "mode": cfg.mode,
                "totalSent": total_sent,
                "checkpoint": {
                    "last_edit": last_edit,
                    "last_code": last_code,
                },
            },
        )
        return 1
    finally:
        conn.close()


def run_once_mock(cfg: AgentConfig) -> int:
    started_at = utc_now_iso()
    last_edit, last_code = load_checkpoint(cfg.checkpoint_file)
    total_sent = 0
    total_created = 0
    total_updated = 0
    total_skipped = 0

    try:
        items = load_fixture_items(cfg, last_edit, last_code)
        if not items:
            log(f"mock done total={total_sent}, checkpoint=({last_edit}, {last_code})")
            save_status(
                cfg.status_file,
                {
                    "startedAt": started_at,
                    "finishedAt": utc_now_iso(),
                    "success": True,
                    "mode": cfg.mode,
                    "inputMode": cfg.input_mode,
                    "fixtureFile": str(cfg.fixture_file),
                    "totalSent": total_sent,
                    "created": total_created,
                    "updated": total_updated,
                    "skipped": total_skipped,
                    "checkpoint": {
                        "last_edit": last_edit,
                        "last_code": last_code,
                    },
                },
            )
            return 0

        for i in range(0, len(items), cfg.batch_size):
            if STOP_REQUESTED:
                log("stopped before mock batch completion")
                return 130

            batch = items[i : i + cfg.batch_size]
            checkpoint = f"{last_edit}|{last_code}" if last_edit else ""
            result = push_batch(cfg, batch, checkpoint)

            total_sent += len(batch)
            total_created += int(result.get("created", 0) or 0)
            total_updated += int(result.get("updated", 0) or 0)
            total_skipped += int(result.get("skipped", 0) or 0)

            tail = batch[-1]
            last_edit = str(tail.get("editDtTime", "") or "")
            last_code = str(tail.get("dealCode", "") or "")
            save_checkpoint(cfg.checkpoint_file, last_edit, last_code)

            log(
                "mock pushed={} total={} server(created={}, updated={}, skipped={}) checkpoint=({}, {})".format(
                    len(batch),
                    total_sent,
                    result.get("created"),
                    result.get("updated"),
                    result.get("skipped"),
                    last_edit,
                    last_code,
                )
            )

        save_status(
            cfg.status_file,
            {
                "startedAt": started_at,
                "finishedAt": utc_now_iso(),
                "success": True,
                "mode": cfg.mode,
                "inputMode": cfg.input_mode,
                "fixtureFile": str(cfg.fixture_file),
                "totalSent": total_sent,
                "created": total_created,
                "updated": total_updated,
                "skipped": total_skipped,
                "checkpoint": {
                    "last_edit": last_edit,
                    "last_code": last_code,
                },
            },
        )
        return 0
    except Exception as exc:  # noqa: BLE001
        log(f"mock sync failed: {exc}")
        save_status(
            cfg.status_file,
            {
                "startedAt": started_at,
                "finishedAt": utc_now_iso(),
                "success": False,
                "error": str(exc),
                "mode": cfg.mode,
                "inputMode": cfg.input_mode,
                "fixtureFile": str(cfg.fixture_file),
                "totalSent": total_sent,
                "checkpoint": {
                    "last_edit": last_edit,
                    "last_code": last_code,
                },
            },
        )
        return 1


def run_once(cfg: AgentConfig) -> int:
    if cfg.input_mode == "mock":
        return run_once_mock(cfg)
    return run_once_db(cfg)


def run_daemon(cfg: AgentConfig) -> int:
    log(f"daemon mode started interval={cfg.interval_seconds}s")
    while not STOP_REQUESTED:
        exit_code = run_once(cfg)
        if STOP_REQUESTED:
            break
        sleep_seconds = cfg.interval_seconds if exit_code == 0 else min(cfg.interval_seconds, 30)
        for _ in range(sleep_seconds):
            if STOP_REQUESTED:
                break
            time.sleep(1)
    log("daemon mode stopped")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Local DDeal -> Cloud client sync agent")
    parser.add_argument(
        "--mode",
        choices=["once", "daemon"],
        default=None,
        help="override SYNC_MODE (once|daemon)",
    )
    parser.add_argument(
        "--interval-seconds",
        type=int,
        default=None,
        help="override SYNC_INTERVAL_SECONDS (daemon mode)",
    )
    parser.add_argument(
        "--input-mode",
        choices=["db", "mock"],
        default=None,
        help="override SYNC_INPUT_MODE (db|mock)",
    )
    parser.add_argument(
        "--fixture-file",
        default=None,
        help="override SYNC_FIXTURE_FILE (used when input mode is mock)",
    )
    parser.add_argument(
        "--print-config",
        action="store_true",
        help="print effective config and exit",
    )
    parser.add_argument(
        "--reset-checkpoint",
        action="store_true",
        help="delete checkpoint file before sync (full reload from start)",
    )
    parser.add_argument(
        "--probe-db",
        action="store_true",
        help="print sample DDeal rows with current DB charset and exit",
    )
    return parser.parse_args()


def main() -> int:
    install_signal_handlers()
    args = parse_args()

    try:
        cfg = AgentConfig.from_env()
        if args.mode:
            cfg.mode = args.mode
        if args.interval_seconds is not None:
            cfg.interval_seconds = args.interval_seconds
        if args.input_mode:
            cfg.input_mode = args.input_mode
        if args.fixture_file:
            cfg.fixture_file = Path(args.fixture_file)
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
            "api_url": cfg.api_url,
            "company_id": cfg.company_id,
            "source_name": cfg.source_name,
            "code_prefix": cfg.code_prefix,
            "batch_size": cfg.batch_size,
            "checkpoint_file": str(cfg.checkpoint_file),
            "status_file": str(cfg.status_file),
            "mode": cfg.mode,
            "interval_seconds": cfg.interval_seconds,
            "connect_timeout_seconds": cfg.connect_timeout_seconds,
            "http_timeout_seconds": cfg.http_timeout_seconds,
            "http_verify_ssl": cfg.http_verify_ssl,
            "retry_max": cfg.retry_max,
            "retry_backoff_seconds": cfg.retry_backoff_seconds,
            "input_mode": cfg.input_mode,
            "fixture_file": str(cfg.fixture_file),
            "agent_token_set": bool(cfg.agent_token),
        }
        print(json.dumps(safe, ensure_ascii=False, indent=2))
        return 0

    if args.probe_db:
        try:
            conn = db_connect(cfg)
            rows = query_probe_rows(conn, cfg, limit=20)
            print(
                json.dumps(
                    [
                        {
                            "dealCode": "" if row.get("deal_code") is None else str(row.get("deal_code")),
                            "dealSangho": "" if row.get("Deal_sangho") is None else str(row.get("Deal_sangho")),
                            "dealName": "" if row.get("Deal_name") is None else str(row.get("Deal_name")),
                            "editDtTime": "" if row.get("EditDtTime") is None else str(row.get("EditDtTime")),
                        }
                        for row in rows
                    ],
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        except Exception as exc:  # noqa: BLE001
            log(f"db probe failed: {exc}")
            return 1
        finally:
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass

    if args.reset_checkpoint:
        if cfg.checkpoint_file.exists():
            cfg.checkpoint_file.unlink()
            log(f"checkpoint reset: {cfg.checkpoint_file}")
        else:
            log(f"checkpoint not found, skip reset: {cfg.checkpoint_file}")

    if cfg.mode == "daemon":
        return run_daemon(cfg)
    return run_once(cfg)


if __name__ == "__main__":
    raise SystemExit(main())
