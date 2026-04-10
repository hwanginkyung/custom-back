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

## 3) Local PC quick start

### Linux/mac

```bash
cd scripts
cp local_agent_sync.env.example .env.local-agent
# edit .env.local-agent
pip install mysql-connector-python requests
./run_local_agent_sync.sh
```

### Windows PowerShell

```powershell
cd scripts
Copy-Item .\local_agent_sync.env.example .\.env.local-agent
# edit .env.local-agent
pip install mysql-connector-python requests
.\run_local_agent_sync.ps1
```

## 4) Required env values

```dotenv
SYNC_API_URL=http://43.203.237.154:8080/api/clients/sync/push
CLIENT_SYNC_AGENT_TOKEN=<same-token-as-ec2>
SYNC_COMPANY_ID=10
LOCAL_DB_HOST=127.0.0.1
LOCAL_DB_PORT=3306
LOCAL_DB_NAME=ncustoms
LOCAL_DB_USER=kcba
LOCAL_DB_PASSWORD=...
```

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

## 7) Push API contract

- `POST /api/clients/sync/push`
- Header: `X-Agent-Token: <CLIENT_SYNC_AGENT_TOKEN>`
- Body fields in each item map from DDeal:
  - `dealCode`, `dealSaupgbn`, `dealSaup`, `dealSangho`, `dealName`
  - `dealPost`, `dealJuso`, `dealJuso2`, `dealTel`, `dealFax`, `dealTong`
  - `roadNmCd`, `buldMngNo`, `addDtTime`, `editDtTime`
