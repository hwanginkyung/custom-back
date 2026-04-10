# Local Agent Push Sync

This repo now supports `DDeal -> broker_client` sync via push.

## Backend endpoint

- `POST /api/clients/sync/push`
- Header: `X-Agent-Token: <CLIENT_SYNC_AGENT_TOKEN>`
- Body:

```json
{
  "companyId": 1,
  "source": "local-ddeal-agent",
  "checkpoint": "20260410093000|00123",
  "items": [
    {
      "dealCode": "00123",
      "dealSaupgbn": "A",
      "dealSaup": "1234567890",
      "dealSangho": "Sample Co",
      "dealName": "Hong",
      "dealPost": "04530",
      "dealJuso": "Seoul ...",
      "dealJuso2": "Detail ...",
      "dealTel": "02-1234-5678",
      "dealFax": "02-1234-5679",
      "dealTong": "TONG123",
      "roadNmCd": "02012014",
      "buldMngNo": "0201201400000000000",
      "addDtTime": "20260409120000",
      "editDtTime": "20260410101500"
    }
  ]
}
```

## Server env

Set token on backend:

```bash
CLIENT_SYNC_AGENT_TOKEN=replace-with-strong-secret
```

## Agent script

Use `scripts/local_agent_push_sync.py`.

Install deps:

```bash
pip install mysql-connector-python requests
```

Required env:

```bash
SYNC_API_URL=https://<your-api-host>/api/clients/sync/push
CLIENT_SYNC_AGENT_TOKEN=<same-token-as-backend>
SYNC_COMPANY_ID=<target-company-id>
LOCAL_DB_HOST=127.0.0.1
LOCAL_DB_PORT=3306
LOCAL_DB_NAME=ncustoms
LOCAL_DB_USER=kcba
LOCAL_DB_PASSWORD=...
```

Run once:

```bash
python scripts/local_agent_push_sync.py
```

Then schedule with cron/systemd/task scheduler.

