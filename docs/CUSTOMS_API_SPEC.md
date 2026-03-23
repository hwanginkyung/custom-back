# Customs API Spec

Last updated: 2026-02-19

This document describes the current customs APIs implemented in backend code.

## 1) Endpoints

### 1.1 List and detail

- `GET /api/customs`
  - Query: `stage`, `shipperName`, `from`, `to`, `page`, `size`
  - `stage` values: `WAITING | IN_PROGRESS | DONE`
  - Response: `Page<CustomsListResponse>`

- `GET /api/customs/{vehicleId}`
  - Response: `CustomsDetailResponse`

### 1.2 Draft, submit, resend

- `POST /api/customs/draft`
  - Create minimal draft and return `requestId`.
  - Body: none
  - Response: `CustomsSendResponse` (`status = DRAFT`, `generatedDocuments = []`)

- `POST /api/customs/{requestId}/submit`
  - Final submit with full payload.
  - Body: `CustomsSendRequest`
  - Response: `CustomsSendResponse` (`status = SUBMITTED`)

- `PUT /api/customs/{requestId}/resend`
  - Update and resend existing request.
  - Body: `CustomsSendRequest`
  - Response: `CustomsSendResponse` (`status = SUBMITTED`)

- `POST /api/customs/{requestId}/send`
  - Temporary/mock send action.
  - No real external transmission.
  - Behavior:
    - `SUBMITTED -> PROCESSING`
    - `PROCESSING/COMPLETED`: keep current status (idempotent)
    - `DRAFT`: validation error
  - Response:
    - `customsRequestId`
    - `status`
    - `mode` (`MOCK`)
    - `sentAt`

### 1.3 Generated docs

- `GET /api/customs/{requestId}/docs`
  - Return generated document list.
  - Response: `List<GeneratedDoc>`

- `GET /api/customs/{requestId}/docs/{filename}`
  - Inline preview/download for a single generated PDF.
  - Response: `application/pdf`

- `GET /api/customs/{requestId}/merged.pdf`
  - Merged PDF for all generated docs.
  - Response: `application/pdf`

### 1.4 Uploads

- `POST /api/customs/assets/upload` (multipart)
  - Part: `file`
  - Query:
    - `requestId` (required)
    - `category` (`VEHICLE | CONTAINER`)
    - `vehicleId` (required only when `category=VEHICLE`)
  - Response:
    - `s3Key`
    - `originalFilename`
    - `contentType`
    - `sizeBytes`

- `DELETE /api/customs/assets`
  - Query:
    - `requestId` (required)
    - `category` (`VEHICLE | CONTAINER`)
    - `vehicleId` (required only when `category=VEHICLE`)
    - `s3Key` (required)
  - Behavior:
    - Delete uploaded asset from S3
    - If same key is already linked in saved request data, clear that slot as well
  - Response: `204 No Content`

- `POST /api/customs/upload` (multipart)
  - Part: `file`
  - Upload export certificate for OCR pipeline.
  - Response:
    - `documentId`
    - `s3Key`
    - `jobId`

- `GET /api/customs/upload/{documentId}/snapshot`
  - Export certificate OCR snapshot query by `documentId`
  - Response:
    - `id`
    - `s3Key`
    - `originalFilename`
    - `uploadedAt`
    - `parsedAt`
    - `snapshot`
    - `ocrResult`

- `GET /api/customs/upload/jobs/{jobId}/snapshot`
  - Export certificate OCR snapshot query by `jobId` (alias)
  - Response: same as above

- `PATCH /api/customs/upload/{documentId}/snapshot`
  - Export certificate OCR snapshot manual update by `documentId`
  - Body: `ExportSnapshotUpdateRequest`
  - Response: `200 OK`

## 2) Removed endpoints

- Removed: `POST /api/customs/preview`
- Removed: `POST /api/customs/send`
- Removed: `PUT /api/customs/{requestId}/draft`

## 3) Main request/response contracts

### 3.1 CustomsSendRequest

```json
{
  "shippingMethod": "RORO | CONTAINER",
  "customsBrokerId": 1,
  "customsBrokerName": "optional",
  "vehicles": [
    {
      "vehicleId": 5,
      "price": 0,
      "tradeCondition": "FOB | CIF | CFR",
      "vehiclePhotoS3Keys": ["s3://..."]
    }
  ],
  "containerInfo": {
    "containerNo": "string",
    "sealNo": "string",
    "entryPort": "string",
    "vesselName": "string",
    "exportPort": "string",
    "destinationCountry": "string",
    "consignee": "string"
  },
  "containerPhotoS3Keys": ["s3://..."]
}
```

### 3.2 CustomsSendResponse

```json
{
  "customsRequestId": 123,
  "status": "DRAFT | SUBMITTED",
  "generatedDocuments": [
    {
      "name": "invoice.pdf",
      "downloadUrl": "/api/customs/123/docs/invoice.pdf",
      "sizeBytes": 12345,
      "generatedAt": "2026-02-19T12:34:56Z"
    }
  ]
}
```

## 4) Generated filename rules

`GET /api/customs/{requestId}/docs/{filename}` must use names returned by `generatedDocuments[].name`.

Possible names:

- `invoice.pdf`
- `id_card.pdf` (if available)
- `deregistration.pdf` (single vehicle)
- `deregistration_{VIN_ALNUM}.pdf` (multi vehicle)

## 5) Validation rules (current behavior)

- `POST /api/customs/draft`
  - No body required (requestId only).
- `POST /api/customs/{requestId}/submit` and `PUT /api/customs/{requestId}/resend`
  - `shippingMethod` required: `RORO | CONTAINER`
  - `customsBrokerId` required
  - At least one vehicle required
- `POST /api/customs/{requestId}/send`
  - `DRAFT` 상태에서는 호출 불가
- `RORO`
  - `containerInfo` must be null
  - `containerPhotoS3Keys` and `vehiclePhotoS3Keys` must be empty
- `CONTAINER`
  - `containerInfo` required
  - At least one `containerPhotoS3Key` required
  - At least one `vehiclePhotoS3Key` required per vehicle
- `POST /api/customs/assets/upload`
  - `requestId` must exist
  - `VEHICLE` category requires `vehicleId`
- `DELETE /api/customs/assets`
  - `requestId` must exist
  - `VEHICLE` category requires `vehicleId`
  - `s3Key` must match request/category prefix:
    - `customs-requests/{companyId}/{requestId}/vehicle-{vehicleId}/...`
    - `customs-requests/{companyId}/{requestId}/container/...`

## 6) Recommended client flow

### CONTAINER

1. `POST /api/customs/draft` (requestId 발급)
2. `POST /api/customs/assets/upload` (차량/컨테이너 사진 업로드 반복)
3. `DELETE /api/customs/assets` (필요 시 X 버튼 삭제)
4. `POST /api/customs/{requestId}/submit` (최종 payload + 업로드된 s3Key 포함)
5. `POST /api/customs/{requestId}/send` (임시 mock 보내기)
6. `GET /api/customs/{requestId}/docs` / `GET /api/customs/{requestId}/docs/{filename}` (목록/미리보기)

### RORO

1. `POST /api/customs/draft` (requestId 발급)
2. `POST /api/customs/{requestId}/submit` (최종 payload, 사진 키 없음)
3. `POST /api/customs/{requestId}/send` (임시 mock 보내기)
4. `GET /api/customs/{requestId}/docs` / `GET /api/customs/{requestId}/docs/{filename}` (목록/미리보기)
