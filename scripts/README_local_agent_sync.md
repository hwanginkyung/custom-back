# Local Agent Push Sync (DDeal -> Customs)

Use this when NCustoms DB is local-only (office network) and EC2 cannot pull directly.

## 1) Backend requirement (EC2)

`/home/ubuntu/docker-compose.yml` must include:

```yaml
environment:
  CLIENT_SYNC_AGENT_TOKEN: <strong-random-token>
```

Apply with build:

```bash
cd /home/ubuntu
docker compose up -d --build --force-recreate app
```

## 2) Agent files

- Main script: `scripts/local_agent_push_sync.py`
- Linux/mac wrapper: `scripts/run_local_agent_sync.sh`
- Windows wrapper: `scripts/run_local_agent_sync.ps1`
- Env template: `scripts/local_agent_sync.env.example`
- Mock fixture sample: `scripts/fixtures/ddeal_sample.json`

## 3) Local PC quick start

### Linux/mac

```bash
cd scripts
cp local_agent_sync.env.example .env.local-agent
# edit .env.local-agent
pip install mysql-connector-python requests
./run_local_agent_sync.sh ./.env.local-agent
```

### Windows PowerShell

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
cd scripts
Copy-Item .\local_agent_sync.env.example .\.env.local-agent
# edit .env.local-agent
pip install mysql-connector-python requests
.\run_local_agent_sync.ps1 -EnvFile .\.env.local-agent
```

## 4) Required env values

See `local_agent_sync.env.example` for full list.

Minimum values:

```dotenv
SYNC_API_URL=http://43.203.237.154:8080/api/clients/sync/push
CLIENT_SYNC_AGENT_TOKEN=<same-token-as-ec2>
SYNC_COMPANY_ID=10
LOCAL_DB_HOST=127.0.0.1
LOCAL_DB_PORT=3306
LOCAL_DB_NAME=ncustoms
LOCAL_DB_USER=kcba
LOCAL_DB_PASSWORD=...
LOCAL_DB_CHARSET=euckr
```

문자 깨짐이 보이면 먼저 `LOCAL_DB_CHARSET`을 확인하세요.

- NCustoms 기본 권장: `euckr`
- 일부 환경 테스트: `utf8mb4`

이미 클라우드에 깨진 데이터가 올라간 상태라면, `--reset-checkpoint`로 전체 재적재를 1회 실행해 덮어써야 복구됩니다.

## 5) What success looks like

Agent stdout example:

```text
[sync] pushed=200 total=200 server(created=40, updated=160, skipped=0) checkpoint=(..., ...)
```

Server log example:

```text
[ClientSync] done companyId=10, source=agent-push:local-ddeal-agent, received=..., created=..., updated=...
```

## 6) Schedule (optional)

### Linux cron (every 5 minutes)

```bash
*/5 * * * * /bin/bash /path/to/scripts/run_local_agent_sync.sh >> /path/to/scripts/local-agent.log 2>&1
```

### Windows Task Scheduler

- Program/script: `powershell.exe`
- Arguments:

```text
-ExecutionPolicy Bypass -File "C:\path\to\scripts\run_local_agent_sync.ps1"
```

## 7) Agent modes

### Once mode (default)

- Incremental sync 1회 실행 후 종료
- 수동 버튼 흐름에 적합

```bash
./run_local_agent_sync.sh ./.env.local-agent --mode once
```

### Daemon mode

- 지정 주기마다 자동 동기화
- 체크포인트 기준 증분 업로드

```bash
./run_local_agent_sync.sh ./.env.local-agent --mode daemon --interval-seconds 300
```

## 8) Input modes

### DB mode (default)

- 로컬 DDeal 테이블을 조회해서 업로드

```bash
./run_local_agent_sync.sh ./.env.local-agent --input-mode db --mode once
```

### DB 인코딩 사전 점검 (권장)

실제 업로드 전에 DDeal 샘플을 먼저 출력해 한글이 정상인지 확인합니다.

```bash
./run_local_agent_sync.sh ./.env.local-agent --probe-db
```

Windows:

```powershell
.\run_local_agent_sync.ps1 -EnvFile .\.env.local-agent --probe-db
```

### 전체 재적재(체크포인트 초기화)

체크포인트를 지우고 처음부터 다시 밀어넣습니다.

```bash
./run_local_agent_sync.sh ./.env.local-agent --mode once --input-mode db --reset-checkpoint
```

Windows:

```powershell
.\run_local_agent_sync.ps1 -EnvFile .\.env.local-agent --mode once --input-mode db --reset-checkpoint
```

### Mock mode (test)

- DB 없이 fixture JSON을 업로드
- 프론트/백엔드 API 연동 테스트용

```bash
./run_local_agent_sync.sh ./.env.local-agent --input-mode mock --fixture-file ./fixtures/ddeal_sample.json --mode once
```

## 9) Output files

- Checkpoint: `SYNC_CHECKPOINT_FILE` (default `./.client-sync-checkpoint.json`)
- Last status: `SYNC_STATUS_FILE` (default `./.client-sync-status.json`)

Example status:

```json
{
  "success": true,
  "totalSent": 200,
  "created": 40,
  "updated": 160
}
```

## 10) Push API contract

- `POST /api/clients/sync/push`
- Header: `X-Agent-Token: <CLIENT_SYNC_AGENT_TOKEN>`
- Body fields in each item map from DDeal:
  - `dealCode`, `dealSaupgbn`, `dealSaup`, `dealSangho`, `dealName`
  - `dealPost`, `dealJuso`, `dealJuso2`, `dealTel`, `dealFax`, `dealTong`
  - `roadNmCd`, `buldMngNo`, `addDtTime`, `editDtTime`
