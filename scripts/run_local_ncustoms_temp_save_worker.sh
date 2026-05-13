#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENV_FILE="${SCRIPT_DIR}/.env.local-agent"
if [[ $# -gt 0 && "${1}" != -* ]]; then
  ENV_FILE="$1"
  shift
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[temp-save] missing env file: ${ENV_FILE}" >&2
  echo "[temp-save] copy template: cp ${SCRIPT_DIR}/local_agent_sync.env.example ${SCRIPT_DIR}/.env.local-agent" >&2
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

if ! command -v python3 >/dev/null 2>&1; then
  echo "[temp-save] python3 not found" >&2
  exit 1
fi

exec python3 "${SCRIPT_DIR}/local_ncustoms_temp_save_worker.py" "$@"
