#!/usr/bin/env python3
"""
Local DDeal -> Cloud clients sync (push model).

Dependencies:
  pip install mysql-connector-python requests
"""

import json
import os
import sys
from pathlib import Path
from typing import Dict, List, Tuple

import mysql.connector
import requests


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


DB_HOST = env("LOCAL_DB_HOST", "127.0.0.1")
DB_PORT = int(env("LOCAL_DB_PORT", "3306"))
DB_NAME = env("LOCAL_DB_NAME", "ncustoms")
DB_USER = env("LOCAL_DB_USER", "kcba")
DB_PASSWORD = env("LOCAL_DB_PASSWORD", "")
DB_CHARSET = env("LOCAL_DB_CHARSET", "euckr")

API_URL = env("SYNC_API_URL")
AGENT_TOKEN = env("CLIENT_SYNC_AGENT_TOKEN")
COMPANY_ID = int(env("SYNC_COMPANY_ID", "0"))
SOURCE_NAME = env("SYNC_SOURCE", "local-ddeal-agent")
CODE_PREFIX = env("SYNC_CODE_PREFIX", "00")
BATCH_SIZE = int(env("SYNC_BATCH_SIZE", "200"))
CHECKPOINT_FILE = Path(env("SYNC_CHECKPOINT_FILE", "./.client-sync-checkpoint.json"))


def load_checkpoint() -> Tuple[str, str]:
    if not CHECKPOINT_FILE.exists():
        return "", ""
    payload = json.loads(CHECKPOINT_FILE.read_text(encoding="utf-8"))
    return payload.get("last_edit", ""), payload.get("last_code", "")


def save_checkpoint(last_edit: str, last_code: str) -> None:
    CHECKPOINT_FILE.write_text(
        json.dumps({"last_edit": last_edit, "last_code": last_code}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def require_config() -> None:
    if not API_URL:
        raise RuntimeError("SYNC_API_URL is required")
    if not AGENT_TOKEN:
        raise RuntimeError("CLIENT_SYNC_AGENT_TOKEN is required")
    if COMPANY_ID <= 0:
        raise RuntimeError("SYNC_COMPANY_ID must be > 0")


def query_batch(conn, last_edit: str, last_code: str) -> List[Dict]:
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
                "prefix": f"{CODE_PREFIX}%",
                "last_edit": last_edit,
                "last_code": last_code,
                "batch": BATCH_SIZE,
            },
        )
        return cur.fetchall()


def to_payload_row(row: Dict) -> Dict:
    def val(key: str):
        v = row.get(key)
        return "" if v is None else str(v)

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


def push_batch(rows: List[Dict], checkpoint: str) -> Dict:
    payload = {
        "companyId": COMPANY_ID,
        "source": SOURCE_NAME,
        "checkpoint": checkpoint,
        "items": [to_payload_row(r) for r in rows],
    }
    response = requests.post(
        API_URL,
        headers={
            "Content-Type": "application/json",
            "X-Agent-Token": AGENT_TOKEN,
        },
        json=payload,
        timeout=20,
    )
    response.raise_for_status()
    return response.json()


def main() -> int:
    try:
        require_config()
        conn = mysql.connector.connect(
            host=DB_HOST,
            port=DB_PORT,
            database=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
            charset=DB_CHARSET,
            use_unicode=True,
            autocommit=True,
        )
    except Exception as exc:
        print(f"[sync] startup failed: {exc}", file=sys.stderr)
        return 1

    last_edit, last_code = load_checkpoint()
    total_sent = 0

    try:
        while True:
            batch = query_batch(conn, last_edit, last_code)
            if not batch:
                print(f"[sync] done. total_sent={total_sent}, checkpoint=({last_edit}, {last_code})")
                return 0

            checkpoint = f"{last_edit}|{last_code}" if last_edit else ""
            result = push_batch(batch, checkpoint)

            total_sent += len(batch)
            tail = batch[-1]
            last_edit = "" if tail.get("EditDtTime") is None else str(tail["EditDtTime"])
            last_code = "" if tail.get("deal_code") is None else str(tail["deal_code"])
            save_checkpoint(last_edit, last_code)

            print(
                f"[sync] pushed={len(batch)} total={total_sent} "
                f"server(created={result.get('created')}, updated={result.get('updated')}, skipped={result.get('skipped')}) "
                f"checkpoint=({last_edit}, {last_code})"
            )
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())

