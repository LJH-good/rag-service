# RAG Storage · LangChain — API 명세

> 소스 기준: `rag-storage-service` 컨트롤러·DTO·`application.yml`, `langchain-service` `app/langchain_service/api/v1/*` (2026-06, AI Connect QA 오케스트레이션·`/retrieve` 반영)

## 공통 규약

포탈·어드민 문서/카테고리 API는 Control Plane Gateway의 `/api/rag/**` 라우트와 맞추기 위해  
`/api/rag/portal/**`, `/api/rag/admin/**` 접두를 사용합니다.

| 구분 | 노출 조건 |
|------|-----------|
| 문서·카테고리·파이프라인 API | `rag.app.role=api` 프로파일 (`local-api`, `dev-api` 등) |
| RAG 검색·citation API (`RagRetrieveController`, `RagQaController`) | `rag.app.role=api` (`local-api`, `dev-api` 등) |

### 개인 RAG 카테고리 ID

| 설정 | 환경 변수 | 설명 |
|------|-----------|------|
| `rag.categories.personal-category-id` | `RAG_PERSONAL_CATEGORY_ID` | 개인 문서·QA용 고정 카테고리 UUID |

- 클라이언트/GW는 개인 문서·QA 시 이 ID를 `categoryId`로 전달합니다.
- `categoryId`가 **null·빈 값**이거나 위 ID와 **동일**하면 **개인 RAG**로 처리합니다.
- 그 외 활성 카테고리 UUID는 **사내 RAG**(`payload.category_id` 필터)입니다.
- 로컬 기본값: `61911ccb-8733-4b2b-9476-25d2347605a9` (`application-local-api.yml`, langchain `.env.example`)
- **LangChain** (`app/vectorstore/personal_category.py`)은 PCC ingest 시 동일 규칙으로 Qdrant 필터를 구성합니다.

### 헤더

| 헤더 | 필수 여부 | 설명 |
|------|---------|------|
| `X-User-No` | 포탈 문서·카테고리·업로드·QA 필수 | 사용자 UUID |
| `X-Transaction-Id` | 업로드·QA 필수 | 요청 추적 UUID (없으면 400) |
| `X-API-Key` | QA 필수 | 사용자 API 키 (AI Gateway UAK 등) |
| `Content-Type` | QA·카테고리 JSON | `application/json` |

> 어드민 데이터소스·카테고리 수정/삭제 API는 `X-User-No`를 요구하지 않습니다.  
> 어드민 카테고리 **목록**·**생성**만 `X-User-No`를 사용합니다(목록 필터·소유자 기록).

### 공통 에러 응답

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "document not found: 550e8400-...",
  "timestamp": "2026-05-22T10:00:00Z"
}
```

필수 헤더 누락 시 Spring이 `MissingRequestHeaderException`을 반환하면 `code`는 `BAD_REQUEST_HEADER_REQUIRED`입니다.

### HTTP 상태 코드 정책

| 상태 | 의미 |
|------|------|
| 200 | 성공 |
| 201 | 생성 완료 (`Location` 헤더 포함) |
| 204 | 삭제 완료 (본문 없음) |
| 400 | 요청 파라미터 오류 |
| 404 | 리소스 없음 |
| 409 | 충돌 (동시 업로드, 이미 삭제된 리소스 등) |
| 502 | 외부 서비스(스토리지 등) 오류 |

### fileStatus 값 (`rag_document_files.status`)

| 값 | 의미 |
|----|------|
| `PENDING` | 파일 row 생성, 스토리지 업로드 전 |
| `UPLOADED` | 스토리지 업로드 완료, 파이프라인 진행 중 |
| `INDEXED` | 파이프라인 완료 (벡터 DB 색인 성공) |
| `FAILED` | 업로드 또는 파이프라인 실패 |

### processingStatus 값 (UI 표시용)

포탈 목록·상세·파이프라인 Job 조회 응답에 포함됩니다. **화면의 성공/실패/처리 중 판단은 이 값을 우선**하고, raw `jobStatus`·`fileStatus`만으로 실패 UI를 띄우지 않습니다.

| 값 | 의미 |
|----|------|
| `PROCESSING` | 파이프라인 진행 중 (Job `PENDING`/`RUNNING`, 파일 `UPLOADED` 등) |
| `SUCCEEDED` | 완료 (`fileStatus=INDEXED` 또는 Job `SUCCEEDED`) |
| `FAILED` | 확정 실패 (Job `FAILED` 또는 `fileStatus=FAILED`) |

| 필드 | 타입 | 설명 |
|------|------|------|
| `processingStatus` | enum | 위 표 기준 UI 상태 |
| `progressPercent` | int | 단계 기준 진행률 5~100 (§3.1 참고) |
| `terminal` | boolean | `true`이면 Job이 종료됨 (`SUCCEEDED` 또는 `FAILED`). 업로드 폴링 중단 조건 |

> 일시 장애·단계 간 레이스는 서버가 Job을 `PENDING`으로 재큐잉하며 `fileStatus`를 `FAILED`로 내리지 않습니다.  
> 업로드 직후 진행 표시는 **문서 목록이 아니라** `GET /api/rag/pipeline/jobs/{jobId}` 폴링을 권장합니다 (§3.0).

---

## 1. 문서 API (포탈/어드민 공통)

문서(=데이터소스)의 업로드/조회/다운로드/삭제/처리상세는 포탈과 어드민에서 공통으로 사용합니다.  
차이는 **접근 경로와 조회 범위**입니다.

### 1.1 파일 업로드

```
POST /api/rag/{aiServiceName}/documents/upload
Content-Type: multipart/form-data
X-User-No: {uuid}
X-Transaction-Id: {uuid}
```

EMBED(문서 청크 임베딩) 과금 UAK는 업로드 헤더가 아니라 **`rag_documents.user_no` → `user_access_key`** 조회로 결정됩니다.  
`userType=admin` 이고 해당 `user_no`에 NORMAL UAK가 없으면 consumer가 `__admin_service__` UAK로 fallback 합니다.

**요청 파트**

| 파트 | 필수 | 설명 |
|------|------|------|
| `file` | ✅ | 업로드 파일 |
| `categoryId` | ✅ (파트 존재) | 카테고리 UUID. 개인 업로드(`userType=user`)는 서버가 `RAG_PERSONAL_CATEGORY_ID`로 강제 저장 |
| `title` | ❌ | 파일 제목 (미지정 시 원본 파일명 사용) |
| `userType` | ✅ | `user` / `admin` 또는 GW 호환: `USER` → user, `COMPANY`·`ADMIN` → admin |

**업로드 scope 규칙**

- 호출 파라미터 자체(`file/categoryId/title/userType`)는 기존과 동일합니다.
- `userType=user`(개인)인 경우, 서버는 요청 `categoryId` 값과 무관하게 `RAG_PERSONAL_CATEGORY_ID`를 사용합니다.
- `userType=admin`(사내)인 경우, 요청 `categoryId`를 그대로 사용합니다. 빈 문자열이면 `RAG_PERSONAL_CATEGORY_ID`로 대체합니다.

**파일 크기 제한**

| 설정 | 기본값 (`application.yml`) | local-api / dev-api |
|------|------------------------------|---------------------|
| `rag.upload.max-file-size-bytes` | `20971520` (20MB) | `57671680` (~55MB) |
| `rag.upload.extension-limits` | — | 확장자별 개별 한도(선택) |

> multipart 한도(`spring.servlet.multipart.max-file-size`)는 local-api/dev-api에서 `72MB`이지만,  
> 업로드 비즈니스 한도는 `rag.upload.max-file-size-bytes`가 우선 적용됩니다.  
> 초과 시 `400 BAD_REQUEST_FILE_SIZE_EXCEEDED`.

**응답 200**

응답 헤더에 요청과 동일한 `X-Transaction-Id`가 포함됩니다.

```json
{
  "documentId": "550e8400-...",
  "fileId": "660e8400-...",
  "categoryId": "770e8400-...",
  "originalFileName": "manual.pdf",
  "fileSize": 1048576,
  "jobId": "880e8400-...",
  "transactionId": "990e8400-...",
  "createdAt": "2026-05-22T10:00:00Z"
}
```

| 필드 | 설명 |
|------|------|
| `jobId` | 파이프라인 Job ID — 업로드 후 진행 폴링에 사용 (§3.0) |
| `transactionId` | 요청 `X-Transaction-Id`와 동일 — `jobId` 분실 시 최신 Job 조회에 사용 (§3.2) |

**프론트 연동 (업로드 직후)**

1. 응답의 `jobId`를 저장한다.
2. `GET /api/rag/pipeline/jobs/{jobId}`를 2~3초 간격으로 폴링한다.
3. `terminal === true`이면 폴링을 멈춘다.
4. `terminal === true && processingStatus === 'FAILED'`일 때만 실패 UI를 표시한다.
5. 완료 후 문서 목록은 1회 갱신한다 (`GET /api/rag/portal/documents`).

**주요 오류**

| HTTP | code | 상황 |
|------|------|------|
| 400 | `BAD_REQUEST_TRANSACTION_ID_REQUIRED` | `X-Transaction-Id` 누락 |
| 400 | `BAD_REQUEST_FILE_REQUIRED` | `file` 파트 누락/비어 있음 |
| 400 | `BAD_REQUEST_CATEGORY_ID_REQUIRED` | `categoryId` UUID 형식 아님 |
| 400 | `BAD_REQUEST_USER_TYPE_REQUIRED` | `userType` 누락 |
| 400 | `BAD_REQUEST_DOCUMENT_SCOPE_INVALID` | 지원하지 않는 `userType` |
| 400 | `CATEGORY_INACTIVE` | 비활성 카테고리 |
| 409 | `CONFLICT_UPLOAD_ALREADY_IN_PROGRESS` | 동일 `userNo`에 활성 Job 존재 |
| 502 | `STORAGE_REQUEST_ERROR` | MinIO 업로드 실패 |

---

### 1.2 문서/데이터소스 목록 조회 (공통)

```
GET /api/rag/portal/documents
GET /api/rag/admin/datasources
GET /api/rag/knowledge/datasources          (어드민과 동일, Knowledge BFF용)
X-User-No: {uuid}                           (포탈만 필수)
```

**쿼리 파라미터**

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `categoryId` | — | 카테고리 필터 |
| `uncategorized` | — | `true` 시 카테고리 미지정 문서만 (어드민 전용) |
| `userType` | — | `user` / `admin` (어드민·Knowledge 전용) |
| `search` | — | 제목/파일명 검색어 (부분 일치, 대소문자 무시) |
| `searchField` | `ALL` | `ALL` \| `TITLE` \| `ORIGINAL_FILE_NAME` \| `CATEGORY_NAME` |
| `page` | 0 | 페이지 번호 |
| `size` | 20 | 페이지 크기 |

**경로별 범위**

| 경로 | 범위 |
|------|------|
| `/api/rag/portal/documents` | `X-User-No` 본인 문서만 |
| `/api/rag/admin/datasources` | 전체 데이터소스 |
| `/api/rag/knowledge/datasources` | 어드민과 동일 |

**응답 200 — 포탈 (`DocumentListItem`)**

```json
{
  "items": [
    {
      "documentId": "550e8400-...",
      "fileId": "660e8400-...",
      "categoryId": "770e8400-...",
      "userType": "user",
      "userNo": "...",
      "title": "사용 설명서",
      "originalFileName": "manual.pdf",
      "fileSize": 1048576,
      "fileStatus": "INDEXED",
      "jobStatus": "SUCCEEDED",
      "currentStep": null,
      "jobId": "880e8400-...",
      "processingStatus": "SUCCEEDED",
      "progressPercent": 100,
      "terminal": true,
      "createdAt": "2026-05-22T10:00:00Z",
      "updatedAt": "2026-05-22T10:05:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

**포탈 목록 UI**

| 화면 | 권장 필드 |
|------|-----------|
| 처리 중 / 완료 / 실패 라벨 | `processingStatus` |
| 진행률(선택) | `progressPercent` |
| 상세 raw 상태 | `jobStatus`, `fileStatus` (디버그·관리용) |

**응답 200 — 어드민 (`DataSourceListItem`)**

포탈 목록과 동일하게 `processingStatus`·`progressPercent`·`terminal`을 포함합니다. `categoryName`·`updatedAt`을 추가로 제공합니다.

---

### 1.3 문서/데이터소스 상세 조회 (공통)

```
GET /api/rag/portal/documents/{documentId}
GET /api/rag/admin/datasources/{documentId}
GET /api/rag/knowledge/datasources/{documentId}
X-User-No: {uuid}   (포탈만 필수)
```

> 포탈 경로는 본인 문서만 조회 가능, 어드민·Knowledge 경로는 전체 대상 조회 가능.

**응답 200 — 포탈 (`DocumentDetailResponse`)**

```json
{
  "documentId": "550e8400-...",
  "fileId": "660e8400-...",
  "categoryId": "770e8400-...",
  "userType": "user",
  "userNo": "878ae58e-242f-41c5-885e-d931ac4e89f9",
  "title": "사용 설명서",
  "originalFileName": "manual.pdf",
  "fileSize": 1048576,
  "checksum": "abc123...",
  "fileStatus": "INDEXED",
  "processingStatus": "SUCCEEDED",
  "progressPercent": 100,
  "terminal": true,
  "latestJob": {
    "jobId": "880e8400-...",
    "status": "SUCCEEDED",
    "currentStep": null,
    "triggerType": "UPLOAD",
    "errorCode": null,
    "errorMessage": null,
    "startedAt": "2026-05-22T10:01:00Z",
    "endedAt": "2026-05-22T10:05:00Z",
    "createdAt": "2026-05-22T10:00:30Z"
  },
  "jobs": [ "..." ],
  "categoryName": "사내 정책",
  "createdAt": "2026-05-22T10:00:00Z"
}
```

**응답 200 — 어드민 (`DataSourceDetailResponse`)**

포탈 상세와 동일한 Job·카테고리 정보에 더해 `indexInfo`(벡터 색인 메타)를 포함합니다.

```json
{
  "indexInfo": {
    "collection": "rag_chunks",
    "embeddingModel": "text-embedding-3-small",
    "embeddingDim": 1536,
    "indexedAt": "2026-05-22T10:05:00Z"
  }
}
```

> 파이프라인 미완료 시 `indexInfo`는 `null`입니다.

---

### 1.3-bis 문서 처리 상세 조회 (공통)

문서의 처리 상태(청크/진행률/실패 정보)를 조회합니다.

```
GET /api/rag/portal/documents/{documentId}/processing-detail
GET /api/rag/admin/datasources/{documentId}/processing-detail
GET /api/rag/knowledge/datasources/{documentId}/processing-detail
X-User-No: {uuid}   (포탈만 필수)
```

**쿼리 파라미터**

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `includeChunkText` | `true` | 청크 원문 미리보기 포함 여부 |
| `chunkLimit` | `50` | 반환할 청크 개수 |
| `previewChars` | `500` | 청크 미리보기 최대 글자수 |

**응답 200 (`DataSourceProcessingDetailResponse`)**

```json
{
  "documentId": "550e8400-...",
  "fileId": "660e8400-...",
  "categoryId": "770e8400-...",
  "categoryName": "사내 정책",
  "userType": "user",
  "userNo": "...",
  "title": "사용 설명서",
  "originalFileName": "manual.pdf",
  "fileSize": 1048576,
  "checksum": "abc123...",
  "fileStatus": "INDEXED",
  "pipeline": {
    "status": "SUCCEEDED",
    "currentStep": null,
    "failedStep": null,
    "errorCode": null,
    "errorMessage": null,
    "progressPercent": 100,
    "totalChunks": 42,
    "totalParts": 4,
    "readyParts": 0,
    "upsertedParts": 4,
    "failedParts": 0,
    "totalPoints": 1200
  },
  "jobs": [ "..." ],
  "chunks": [
    {
      "chunkId": "aa6d3e94-...",
      "chunkIndex": 0,
      "charCount": 3842,
      "storageKey": "rag/.../chunks/0.txt",
      "embeddingStatus": "UPSERTED",
      "pointCount": 300,
      "textPreview": "환불은 구매 후 30일 이내에..."
    }
  ],
  "createdAt": "2026-05-22T10:00:00Z"
}
```

| `embeddingStatus` | 의미 |
|-------------------|------|
| `READY` | 임베딩 생성 대기 |
| `UPSERTED` | Qdrant 적재 완료 |
| `FAILED` | 임베딩/적재 실패 |

---

### 1.4 파일 다운로드 (공통)

```
GET /api/rag/portal/documents/{documentId}/download
GET /api/rag/admin/datasources/{documentId}/download
GET /api/rag/knowledge/datasources/{documentId}/download
X-User-No: {uuid}              (포탈만 필수)
X-Transaction-Id: {uuid}         (선택, 로그 추적용)
```

**응답 200**

```
Content-Type: application/octet-stream
Content-Disposition: attachment; filename*=UTF-8''manual.pdf
```

바이너리 파일 데이터

---

### 1.5 문서/데이터소스 삭제 (공통)

```
DELETE /api/rag/portal/documents/{documentId}
DELETE /api/rag/admin/datasources/{documentId}
DELETE /api/rag/knowledge/datasources/{documentId}
X-User-No: {uuid}   (포탈만 필수)
```

**응답 204** (soft-delete)

> 이미 삭제된 문서/데이터소스는 `409` (`DATASOURCE_ALREADY_DELETED`).  
> **삭제된 문서는 복구할 수 없습니다.**

**물리 데이터 보관 정책**

| 시점 | DB | MinIO 원본 | Qdrant |
|------|-----|-----------|--------|
| soft-delete 직후 | `is_deleted=true`, `deleted_at` 기록 | 유지 | 유지 |
| 삭제 후 N일 경과 | 행 유지 (로그용) | 제거 | 제거 |
| 퍼지 완료 | `purged_at` 기록, `storage_key` 비움 | — | — |

스케줄: `rag.purge.enabled=true`, `rag.purge.retention-days=100` (기본 cron: 매일 03:00)

---

## 2. 카테고리 API

### 2.1 어드민 — 카테고리 목록 조회

```
GET /api/rag/admin/categories
GET /api/rag/categories                        (별칭)
X-User-No: {uuid}   (선택: 지정 시 해당 사용자 카테고리만 반환)
?search={keyword}                              (선택: 부분 일치, 대소문자 무시)
?searchField=ALL|NAME|DESCRIPTION            (선택, 기본 ALL)
?active=true|false                           (선택: 활성/비활성 필터, 미지정 시 전체)
```

**응답 200**

```json
[
  {
    "categoryId": "770e8400-...",
    "name": "사내 정책",
    "description": "사내 규정·정책 문서",
    "active": true,
    "userNo": "...",
    "createdAt": "2026-05-22T09:00:00Z",
    "updatedAt": "2026-05-22T09:00:00Z"
  }
]
```

---

### 2.1-bis 포탈 — 카테고리 목록 조회

```
GET /api/rag/portal/categories
X-User-No: {uuid}
?search={keyword}
?searchField=ALL|NAME|DESCRIPTION
```

**응답 200** — `active=true` 인 카테고리만 반환 (비활성·삭제된 카테고리 제외)

---

### 2.2 카테고리 생성

```
POST /api/rag/admin/categories
POST /api/rag/categories                       (별칭)
X-User-No: {uuid}
Content-Type: application/json
```

**요청 본문**

| 필드 | 필수 | 설명 |
|------|------|------|
| `name` | ✅ | 카테고리명 (최대 100자) |
| `description` | ❌ | 설명 (최대 500자) |
| `active` | ❌ | 활성 여부 (기본 `true`) |

```json
{
  "name": "신규 카테고리",
  "description": "선택 설명",
  "active": true
}
```

**응답 201** — 생성된 카테고리 + `Location: /api/rag/admin/categories/{categoryId}`

---

### 2.3 카테고리 수정

```
PUT /api/rag/admin/categories/{categoryId}
PUT /api/rag/categories/{categoryId}           (별칭)
Content-Type: application/json
```

**요청 본문**

| 필드 | 필수 | 설명 |
|------|------|------|
| `name` | ✅ | 카테고리명 |
| `description` | ❌ | 설명 (`null`이면 기존 값 유지) |
| `active` | ❌ | 활성 여부 (`null`이면 기존 값 유지) |

**응답 200** — 변경된 카테고리

> 포탈 문서·카테고리 조회는 `active=false` 카테고리를 제외한다. 업로드·QA 시 비활성 카테고리 지정 시 `400 CATEGORY_INACTIVE`.

---

### 2.4 카테고리 삭제

```
DELETE /api/rag/admin/categories/{categoryId}
DELETE /api/rag/categories/{categoryId}        (별칭)
```

**응답 204** (soft-delete)

---

## 3. 파이프라인 API (공통)

업로드 등으로 생성된 Job 상태를 폴링할 수 있습니다.

### 3.0 업로드 진행 상태 폴링 (프론트 권장)

```
[업로드] POST /api/rag/{aiServiceName}/documents/upload
    → jobId, transactionId 저장
    ↓
[폴링] GET /api/rag/pipeline/jobs/{jobId}   (2~3초 간격)
    → terminal=false  → 처리 중 UI (processingStatus=PROCESSING, progressPercent)
    → terminal=true & processingStatus=SUCCEEDED → 완료, 목록 1회 갱신
    → terminal=true & processingStatus=FAILED  → 실패 UI (errorMessage 표시)
```

| 하지 말 것 | 할 것 |
|------------|--------|
| 업로드 직후 문서 목록만 반복 조회 | `jobId` 고정으로 §3.1 폴링 |
| `status === 'FAILED'`만 보고 실패 UI | `terminal && processingStatus === 'FAILED'` |
| `fileStatus === 'FAILED'`만 보고 실패 UI | 동일 |

`jobId`를 잃은 경우: `GET /api/rag/pipeline/transactions/{transactionId}/latest-job` (§3.2)로 최신 Job을 조회한 뒤 §3.1을 이어갑니다.

---

### 3.1 Job 상태 단순 조회 (폴링용)

```
GET /api/rag/pipeline/jobs/{jobId}
```

**응답 200**

```json
{
  "jobId": "880e8400-...",
  "documentId": "550e8400-...",
  "transactionId": "...",
  "status": "RUNNING",
  "currentStep": "EMBED",
  "triggerType": "UPLOAD",
  "errorCode": null,
  "errorMessage": null,
  "startedAt": "2026-05-22T10:01:00Z",
  "endedAt": null,
  "createdAt": "2026-05-22T10:00:30Z",
  "progressPercent": 75,
  "terminal": false,
  "processingStatus": "PROCESSING"
}
```

**응답 필드 (폴링 UI)**

| 필드 | 설명 |
|------|------|
| `status` | DB Job 상태 (`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED`) |
| `processingStatus` | UI용 (`PROCESSING` / `SUCCEEDED` / `FAILED`) — Job 기준 |
| `progressPercent` | §3.1 progressPercent 표 |
| `terminal` | `true`이면 폴링 종료 |

> 처리 중(`processingStatus=PROCESSING`)에는 `errorCode`·`errorMessage`가 **항상 null**입니다.  
> 임베딩 API 일시 오류는 서버가 재시도하며, 확정 실패(`terminal=true` && `processingStatus=FAILED`)일 때만 오류 문자열이 내려갑니다.  
> 프론트는 `errorMessage`만 보고 토스트를 띄우지 말 것.

**Job 상태 (`RagJobStatus`)**

| 상태 | 의미 |
|------|------|
| `PENDING` | 대기 중 |
| `RUNNING` | 처리 중 |
| `SUCCEEDED` | 완료 |
| `FAILED` | 실패 |

**triggerType**

| 값 | 의미 |
|----|------|
| `UPLOAD` | 문서 업로드로 자동 시작 |
| `REINDEX` | 재인덱싱 요청 |
| `MANUAL` | 수동 실행 |
| `SCHEDULED` | 스케줄러 자동 실행 |

**currentStep** (RUNNING일 때, DB/API 응답 값)

| 단계 | 설명 |
|------|------|
| `PARSE` | 파일 파싱 (PCC 통합 단계 포함) |
| `CLEAN` | 텍스트 정제 |
| `CHUNK` | 청킹 |
| `EMBED` | 임베딩 생성 |
| `UPSERT` | VectorDB 적재 |

> 워커 내부 라우팅용 `PCC` enum은 DB에 저장되지 않으며, API에는 `PARSE`로 노출됩니다.

**progressPercent 기준**

| 단계 | 진행률 |
|------|--------|
| PENDING (`currentStep` null) | 5% |
| PARSE / PCC | 20% |
| CLEAN | 40% |
| CHUNK | 60% |
| EMBED | 75% |
| UPSERT | 90% |
| SUCCEEDED | 100% |

---

### 3.2 transactionId 기반 최신 Job 조회

업로드 응답에서 `jobId`를 받지 못했을 때, 동일 `X-Transaction-Id`로 생성된 **가장 최근** Job을 조회합니다.

```
GET /api/rag/pipeline/transactions/{transactionId}/latest-job
```

**응답 200** — `JobStatusResponse` (§3.1과 동일 스키마)

```json
{
  "jobId": "880e8400-...",
  "documentId": "550e8400-...",
  "transactionId": "990e8400-...",
  "status": "PENDING",
  "currentStep": null,
  "processingStatus": "PROCESSING",
  "progressPercent": 5,
  "terminal": false
}
```

> 이후 프론트는 응답의 `jobId`로 `GET /api/rag/pipeline/jobs/{jobId}` 폴링을 이어갑니다.

**주요 오류**

| HTTP | code | 상황 |
|------|------|------|
| 404 | `JOB_NOT_FOUND` | 해당 `transactionId`에 Job 없음 |

---

### 3.3 Job 상세 조회 (임베딩 통계 포함)

```
GET /api/rag/pipeline/jobs/{jobId}/detail
```

**응답 200**

```json
{
  "jobId": "880e8400-...",
  "documentId": "550e8400-...",
  "transactionId": "...",
  "status": "RUNNING",
  "currentStep": "UPSERT",
  "triggerType": "UPLOAD",
  "errorCode": null,
  "errorMessage": null,
  "progressPercent": 90,
  "embeddingStats": {
    "totalParts": 4,
    "upsertedParts": 3,
    "failedParts": 0,
    "totalPoints": 1200
  },
  "startedAt": "2026-05-22T10:01:00Z",
  "endedAt": null,
  "createdAt": "2026-05-22T10:00:30Z"
}
```

> `progressPercent` 기준은 §3.1과 동일합니다.

---

### 3.4 문서별 Job 이력 목록 (페이징)

```
GET /api/rag/pipeline/documents/{documentId}/jobs?page=0&size=10
```

**응답 200** — `PagedResponse<JobStatusResponse>`

---

### 3.5 문서별 전체 Job 이력

```
GET /api/rag/pipeline/documents/{documentId}/jobs/all
```

**응답 200** — `List<JobStatusResponse>` (최신순)

---

## 4. QA · RAG 검색 API

클라이언트 QA: **Gateway → RAG `POST /qa`** → 내부 retrieve → **RAG → Gateway → AI Gateway `POST /qa`** → LangChain · LLM → **RAG citation DB 내부 저장** → Client.

```
[TX-A — 클라이언트 QA 트랜잭션]  X-Transaction-Id: Client → Gateway → RAG → AIG → RAG → Client

Client
    │  POST /api/rag/{aiServiceName}/qa
    ↓
  Gateway :9002
    ↓
RAG Storage Service  (RagQaService)
    │
    │  ┌─ [TX-C — 임베딩] retrieve 1단계 (내부) ────────────────────────────┐
    │  │  POST /api/ai/{embeddingAiServiceName}/embedding/request (embedTx) │
    │  │  Gateway :9002 → AI Gateway → Embedding Model → RAG               │
    │  └────────────────────────────────────────────────────────────────────┘
    │
    ├─ Qdrant  POST /collections/{collection}/points/search
    ├─ RagCitationEnricher
    └─ citations[] 구성
    │
    │  POST /api/ai/{aiServiceName}/qa  (RAG → Gateway :9002, rag.gateway.base-url)
    ↓
  Gateway :9002  /api/ai/**
    ↓
AI Gateway  (QaOrchestrationService)
    │
    │  ┌─ [TX-B — LangChain] GET /api/conversation/{sessionId}/context ──┐
    │  │  토큰 예산 선택 + Redis overflow trim → messages·orphan questions  │
    │  └────────────────────────────────────────────────────────────────────┘
    │
    ├─ LLM  (AIG 내부 chat/stream 파이프라인, TX-A)
    ├─ AIG  Redis append  session:{sessionId}:messages | :questions  (TX-A)
    └─ RAG ← { messageId, sessionId, answer, modelName, provider, citations }
    │
    ├─ rag_qa_citation INSERT  (응답 citations[], RAG 내부 — HTTP 없음)
    ↓
  Gateway :9002
    ↓
Client  { messageId, sessionId, answer, modelName, provider, citations }
```

상세·요청/응답 스키마: [`ai-connect-qa-orchestration-spec.md`](./ai-connect-qa-orchestration-spec.md)

### 4.0 클라이언트 QA (E2E)

```
POST /api/rag/{aiServiceName}/qa
X-API-Key: {apiKey}
X-User-No: {uuid}
X-Transaction-Id: {uuid}    ← TX-A
Content-Type: application/json
```

**Gateway 경유 예 (로컬 9002)**

```
POST http://localhost:9002/api/rag/openai/qa
```

**요청 본문 (`AskRequest`)**

| 필드 | 필수 | 설명 |
|------|------|------|
| `sessionId` | ✅ | 채팅방 UUID — Redis `session:{sessionId}:…` |
| `messageId` | ✅ | 메시지 UUID — citation DB 키 |
| `content` | ✅ | 현재 질문 (검색·LLM 공통) |
| `categoryId` | ❌ | retrieve와 동일 검색 모드 |
| `documentId` | ❌ | 문서 범위 한정 |
| `modelCode` | ❌ | LLM 모델 (미지정 시 AIG 자동 라우팅) |

**RAG 내부 처리 (`RagQaService.ask`)**

1. `content`를 `searchQuery`로 **내부 retrieve** (임베딩 TX-C + Qdrant, `topK`는 `rag.qdrant.top-k-default` — 클라이언트 미지정)
2. `POST /api/ai/{svc}/qa` — Gateway 경유 (`rag.gateway.base-url`, `/api/ai/**`)
3. AIG 응답의 **`citations[]`를 DB 저장** (`RagQaCitationService`, citations 비어 있으면 생략)
4. 클라이언트에 AIG 응답 반환

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "messageId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "content": "환불 정책 알려줘",
  "categoryId": "61911ccb-8733-4b2b-9476-25d2347605a9",
  "modelCode": "gpt-4o-mini"
}
```

**응답 200 (`QaAskResponse`)**

| 필드 | 설명 |
|------|------|
| `messageId` | 요청과 동일 |
| `sessionId` | 요청과 동일 |
| `answer` | LLM 답변 |
| `modelName` | 사용 모델 |
| `provider` | AI 서비스 provider |
| `citations` | AIG **최종** citations (no-context 재시도 시 비울 수 있음) |

```json
{
  "messageId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "answer": "환불은 30일 이내...",
  "modelName": "gpt-4o-mini",
  "provider": "openai",
  "citations": [
    {
      "chunkId": "...",
      "documentId": "...",
      "score": 0.91,
      "page": 3,
      "sourceUri": "s3://...",
      "snippet": "..."
    }
  ]
}
```

> 구 monolith `POST /api/rag/{svc}/qa`(LangChain 단독 위임)는 **제거**됨.

### 4.1 RAG 벡터 검색 (citations only)

```
POST /api/rag/{aiServiceName}/retrieve
X-API-Key: {apiKey}
X-User-No: {uuid}
X-Transaction-Id: {uuid}    ← TX-A (클라이언트 QA 트랜잭션)
Content-Type: application/json
```

| 경로 변수 | 설명 |
|-----------|------|
| `aiServiceName` | 경로에 포함되나, 임베딩 모델 라우팅은 `rag.embedding.ai-service-name`(기본 `openai`) 사용 |

**Gateway 경유 예 (로컬 9002)**

```
POST http://localhost:9002/api/rag/openai/retrieve
```

**요청 본문 (`RetrieveRequest`)**

| 필드 | 필수 | 설명 |
|------|------|------|
| `searchQuery` | ✅ | 임베딩용 검색어 (현재 질문 `content`만) |
| `categoryId` | ❌ | 검색 대상 카테고리 UUID (아래 검색 모드 참고) |
| `documentId` | ❌ | 지정 시 해당 문서 범위로 추가 한정 |

**검색 상한 (`topK`)** — 요청 필드 **아님**. RAG 설정 `rag.qdrant.top-k-default` (`RAG_QDRANT_TOP_K_DEFAULT`, 기본 5) 사용.

**검색 모드 (`categoryId`)**

| 조건 | 모드 | Qdrant 필터 |
|------|------|-------------|
| `null` 또는 `RAG_PERSONAL_CATEGORY_ID` 와 동일 | 개인 RAG | `user_type=user` AND `user_no=요청자` |
| 그 외 활성 카테고리 UUID | 사내 RAG | `category_id` 일치 AND `user_type=admin` |

```json
{
  "searchQuery": "[현재 질문]\n\nUser: 환불 정책은?\n\n[이전 대화]\n...",
  "categoryId": "61911ccb-8733-4b2b-9476-25d2347605a9",
  "documentId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**응답 200 (`RetrieveResponse`)**

`answer` 필드는 **없습니다**. citations만 반환합니다.

```json
{
  "citations": [
    {
      "chunkId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "documentId": "550e8400-e29b-41d4-a716-446655440000",
      "score": 0.9100,
      "page": 3,
      "slide": null,
      "sheet": null,
      "sourceUri": "s3://rag/manual.pdf",
      "snippet": "환불은 구매 후 30일 이내에..."
    }
  ]
}
```

| citation 필드 | 설명 |
|---------------|------|
| `chunkId` | Qdrant **point id** 우선, 없으면 payload `chunk_id` |
| `documentId` | 원본 문서 식별자 |
| `score` | 유사도 점수 (소수 4자리) |
| `page` / `slide` / `sheet` | 위치 (없으면 `null`) |
| `sourceUri` | `RagCitationEnricher`가 DB·MinIO 메타로 보강 |
| `snippet` | 청크 본문 미리보기 |

**처리 경로**

1. (선택) 사내 RAG: `categoryId` → `requireUsableCategory`
2. **TX-C** 임베딩: `AiEmbeddingClient` → Gateway `POST /api/ai/{embeddingAiServiceName}/embedding/request` (RAG가 **embedTx UUID** 신규 생성)
3. Qdrant `POST /collections/{collection}/points/search` (`QdrantClient`, Gateway 미경유)
4. score 하한 **0.25**, printable 비율 필터 후 citation 매핑·DB 보강

**선행 조건**

- `rag.gateway.base-url` 설정 (local-api / dev-api)
- Qdrant에 해당 범위 청크 적재
- 요청 `X-API-Key` (UAK)

**주요 오류**

| HTTP | code | 상황 |
|------|------|------|
| 400 | `BAD_REQUEST_TRANSACTION_ID_REQUIRED` | `X-Transaction-Id` 누락 |
| 400 | `BAD_REQUEST_SEARCH_QUERY_REQUIRED` | `searchQuery` 공백 |
| 400 | `CATEGORY_INACTIVE` | 비활성 카테고리 (사내 RAG) |
| 400 | `QUERY_EMBEDDING_EMPTY` | 임베딩 결과 없음 |
| 400 | `QUERY_EMBEDDING_DIMENSION_MISMATCH` | 임베딩 차원 불일치 |
| 502 | `EMBEDDING_API_REQUEST_FAILED` | Gateway·AI Gateway 임베딩 실패 |

**curl 예 (RAG API 직접, local-api :8082)**

```bash
curl -X POST "http://localhost:8082/api/rag/openai/retrieve" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: 00000000-0000-0000-0000-000000000111" \
  -H "X-User-No: 878ae58e-242f-41c5-885e-d931ac4e89f9" \
  -H "X-Transaction-Id: bf84c960-49a6-40f6-8897-65788589e4aa" \
  -d '{"searchQuery":"환불 정책을 알려줘","categoryId":"61911ccb-8733-4b2b-9476-25d2347605a9"}'
```

**검색어:** 클라이언트 **현재 질문(`content`)만** `searchQuery`에 넣는다. LangChain 맥락 문자열은 사용하지 않는다.

> E2E QA는 §4.0 `POST /qa`. citation DB 저장은 §4.0 내부 처리, 조회는 §4.2.

### 4.2 QA citation DB (`rag_qa_citation`)

`rag_qa_log` **미사용**. `message_id`별 citation 저장·조회.

**저장 (공개 API 없음)**

| 항목 | 내용 |
|------|------|
| 시점 | §4.0 `POST /qa` 처리 중, AIG `/qa` 응답 수신 **직후** |
| 구현 | `RagQaCitationService.saveCitations()` (RAG 내부) |
| 입력 | AIG 응답 `messageId`, **`citations[]`** (최종값) |
| 보강 | `RagCitationEnricher` → `rag_qa_citation` INSERT |
| 생략 | `citations`가 비어 있으면 DB 저장 생략 (no-context 등) |

> AIG가 `POST /api/rag/{svc}/qa/citations`를 호출하지 **않음**. HTTP 왕복 없이 RAG `RagQaService`가 처리한다.

**조회**

```
GET /api/rag/{aiServiceName}/qa/messages/{messageId}
X-Transaction-Id: {uuid}    (선택)
```

**응답 200 (`AskResponse`)** — `messageId`, `citations` (답변 본문·modelName 없음)

| HTTP | code | 상황 |
|------|------|------|
| 404 | `QA_CITATIONS_NOT_FOUND` | 해당 `messageId` citation 없음 |

상세: [`ai-connect-qa-orchestration-spec.md`](./ai-connect-qa-orchestration-spec.md) §2.

---

## 5. LangChain Service API (AI Connect · Consumer)

클라이언트는 LangChain을 **직접 호출하지 않습니다**. AI Connect(또는 Consumer PCC)가 내부 URL로 호출합니다.

### 5.1 대화 컨텍스트 (TX-B) — 토큰 예산 선택 + Redis trim

```
GET /api/conversation/{sessionId}/context
X-Transaction-Id: {uuid}
```

요청 **body 없음**. LangChain이 Redis를 읽은 뒤 `select_qa_history_context`로 토큰 예산에 맞게 선택하고, **overflow는 `reconcile_session_storage`로 Redis에서 제거**한다.

**응답 200** — LLM에 넣을 **선택된** 맥락만 반환 (Redis 전체 raw 아님)

```json
{
  "messages": [
    { "role": "user", "content": "...", "metadata": {} },
    { "role": "assistant", "content": "...", "metadata": { "noContext": false } }
  ],
  "questions": [
    { "content": "답변 없었던 이전 질문" }
  ]
}
```

| 필드 | 의미 |
|------|------|
| `messages` | 토큰 예산 안의 **Q&A 쌍** (user/assistant 교차) |
| `questions` | **orphan** 질문만 (쌍 없는 이전 질문) |

- 설정: `get_conversation_options` (`CONVERSATION_*` env · `langchain:config:conversation`)
- AIG는 응답을 그대로 prompt 조립에 사용 (`QaRawHistoryProcessor` 없음)
- QA 턴 append(RPUSH)는 AIG; **trim은 이 API 호출 시 LangChain**

**주요 오류**

| HTTP | code | 상황 |
|------|------|------|
| 400 | `MISSING_TRANSACTION_ID` | Transaction-Id 누락 |
| 400 | `MISSING_SESSION_ID` | path `sessionId` 공백 |

---

### 5.2 PCC — Parse · Clean · Chunk (ingest)

rag-storage Consumer(`RagPccWorker`)가 presigned URL과 함께 호출합니다.  
`rag.pcc.invoke-path` 기본값: `/api/internal/rag/pcc/ingest`

```
POST /api/internal/rag/pcc/ingest
X-Transaction-Id: {uuid}
X-User-No: {uuid}          (개인 categoryId 일 때 필수)
Content-Type: application/json
```

**요청 본문 (`PccIngestRequest`)**

| 필드 | 필수 | 설명 |
|------|------|------|
| `objectUrl` | ✅ | MinIO 등 presigned 다운로드 URL |
| `mimeType` | ❌ | MIME 타입 |
| `originalFileName` | ❌ | 파싱용 파일명 |
| `documentId` | ❌ | 문서 ID (로그·파일명 fallback) |
| `categoryId` | ❌ | 개인/사내 scope 판별 |
| `jobId` | ❌ | Job 추적 |
| `chunk` | ❌ | `{ maxChars, overlapChars, minChars }` (기본 1200/120/40) |

**개인 vs 사내 (`categoryId` + `RAG_PERSONAL_CATEGORY_ID`)**

| scope | 조건 | `X-User-No` |
|-------|------|-------------|
| `personal` | `categoryId` null 또는 개인 UUID | **필수** |
| `corporate` | 그 외 카테고리 UUID | 선택 |

응답 헤더: `X-Transaction-Id`, `X-Pcc-Document-Scope` (`personal` \| `corporate`),  
`X-Pcc-Download-Ms`, `X-Pcc-Parse-Clean-Chunk-Ms`, `X-Pcc-Total-Ms`, `X-Pcc-Chunk-Count`

**응답 200**

```json
{
  "chunks": [
    { "text": "청크 본문...", "index": 0 }
  ]
}
```

**주요 오류**

| HTTP | code | 상황 |
|------|------|------|
| 400 | `MISSING_TRANSACTION_ID` | Transaction-Id 누락 |
| 400 | `MISSING_USER_NO` | 개인 scope인데 User-No 누락 |
| 400 | `OBJECT_DOWNLOAD_FAILED` | presigned URL 다운로드 실패 |
| 408 | `OBJECT_DOWNLOAD_TIMEOUT` | 다운로드 타임아웃 |
| 413 | `OBJECT_TOO_LARGE` | `pcc_max_object_bytes` 초과 |
| 500 | `OBJECT_DOWNLOAD_ERROR` / `PCC_INGEST_ERROR` | 기타 실패 |

---

### 5.3 LangChain 환경 변수 (대화·운영)

| 변수 | 용도 |
|------|------|
| `REDIS_URL` | 대화 히스토리 (`session:{userNo}:*`) |
| `CONVERSATION_MAX_PAIRS` | Redis trim 기본값 (기본 5) |
| `CONVERSATION_SOFT_TOKEN_LIMIT` / `CONVERSATION_HARD_TOKEN_LIMIT` | 토큰 한도 |
| `CONVERSATION_SESSION_TTL_SECONDS` | Redis TTL |
| `ENABLE_INTERNAL_CONVERSATION_CONFIG` | `1`이면 `GET/PATCH/DELETE /internal/config/conversation` |
| `RAG_AIGATEWAY_BASE_URL` / `GATEWAY_URL` | `POST /api/ai/{svc}/chat/stream` 베이스 (Gateway 경유) |
| `RAG_PERSONAL_CATEGORY_ID` | PCC ingest 개인/사내 scope |
| `STREAM_TIMEOUT_SECONDS` | chat stream 타임아웃 |
| `ENABLE_INTERNAL_RAG_VERIFY` | `1`이면 `POST /internal/rag-verify` (임베딩·Qdrant 차원 검증) |
| `QDRANT_URL` / `QDRANT_COLLECTION` | `/ready/rag` 등 헬스체크 (QA 검색은 RAG `/retrieve` 사용) |

---

## 부록 A: 헬스체크

```
GET /actuator/health
GET /actuator/prometheus
```

`management.endpoints.web.exposure.include`: `health`, `prometheus`

---

## 부록 B: 파이프라인 흐름

```
업로드 요청
    │
    ▼
[API 서버] POST /api/rag/{svc}/documents/upload
    │  → RagDocumentFile (PENDING → UPLOADED)
    │  → RagDocument
    │  → RagDocumentJob (PENDING)
    │
    ▼ (폴링) GET /api/rag/pipeline/jobs/{jobId}
    │
[Consumer 서버] 무한루프
    │
    ├─ PCC (LangChain POST /api/internal/rag/pcc/ingest, rag.pcc.langchain-enabled=true 필수)
    │   → presigned objectUrl 다운로드 → parse/clean/chunk
    │   → scope: personal(corporate) = X-Pcc-Document-Scope
    │   → RagChunk 저장
    │   → RagEmbeddingPart (READY)
    │   → Job.currentStep = EMBED
    │
    ├─ EMBED (임베딩 생성)
    │   → Gateway POST /api/ai/{svc}/embedding/request (TX: job transaction_id)
    │   → JSONL 스토리지 저장
    │   → Job.currentStep = UPSERT
    │
    └─ UPSERT (VectorDB 적재)
        → Qdrant PUT /collections/.../points
        → RagEmbeddingPart (UPSERTED)
        → RagIndexMetadata 저장
        → Job.status = SUCCEEDED
        → RagDocumentFile.status = INDEXED
    │
    ▼
[QA] Client → Gateway → RAG  (질문만 retrieve, TX-A/C)
    → RAG → AI Gateway
    ├─ LangChain GET /api/conversation/{sessionId}/context  (TX-B, 선택+Redis trim)
    ├─ AI Gateway LLM
    └─ Redis append (sessionId)
```

파이프라인 **확정 실패** 시 `Job.status=FAILED`, `RagDocumentFile.status=FAILED` 로 전이됩니다.  
일시 장애·단계 간 준비 지연(임베딩 파트·청크 메타 미생성 등)은 Job을 `PENDING`으로 재큐잉하며 파일은 `UPLOADED`를 유지합니다.

---

## 부록 C: API 엔드포인트 요약

| 메서드 | 경로 | 헤더 | role=api |
|--------|------|------|----------|
| POST | `/api/rag/{aiServiceName}/documents/upload` | User-No, Transaction-Id | ✅ |
| GET | `/api/rag/portal/documents` | User-No | ✅ |
| GET | `/api/rag/portal/documents/{id}` | User-No | ✅ |
| GET | `/api/rag/portal/documents/{id}/processing-detail` | User-No | ✅ |
| GET | `/api/rag/portal/documents/{id}/download` | User-No | ✅ |
| DELETE | `/api/rag/portal/documents/{id}` | User-No | ✅ |
| GET | `/api/rag/admin/datasources` | — | ✅ |
| GET | `/api/rag/knowledge/datasources` | — | ✅ |
| GET | `/api/rag/admin/datasources/{id}` | — | ✅ |
| GET | `/api/rag/admin/datasources/{id}/processing-detail` | — | ✅ |
| GET | `/api/rag/admin/datasources/{id}/download` | — | ✅ |
| DELETE | `/api/rag/admin/datasources/{id}` | — | ✅ |
| GET | `/api/rag/portal/categories` | User-No | ✅ |
| GET | `/api/rag/admin/categories` | User-No (선택) | ✅ |
| POST | `/api/rag/admin/categories` | User-No | ✅ |
| PUT | `/api/rag/admin/categories/{id}` | — | ✅ |
| DELETE | `/api/rag/admin/categories/{id}` | — | ✅ |
| GET | `/api/rag/pipeline/jobs/{jobId}` | — | ✅ |
| GET | `/api/rag/pipeline/transactions/{transactionId}/latest-job` | — | ✅ |
| GET | `/api/rag/pipeline/jobs/{jobId}/detail` | — | ✅ |
| GET | `/api/rag/pipeline/documents/{id}/jobs` | — | ✅ |
| GET | `/api/rag/pipeline/documents/{id}/jobs/all` | — | ✅ |
| POST | `/api/rag/{aiServiceName}/retrieve` | API-Key, User-No, Transaction-Id | ✅ |

### LangChain Service (`langchain-service`)

| 메서드 | 경로 | 헤더 | 호출 주체 |
|--------|------|------|-----------|
| GET | `/api/conversation/{sessionId}/context` | Transaction-Id | AI Gateway (TX-B) |
| POST | `/api/ai/{aiServiceName}/chat/stream` | API-Key, Transaction-Id, Session-Id | 클라이언트·Gateway |
| POST | `/api/internal/rag/pcc/ingest` | Transaction-Id, User-No(개인 시) | rag-storage Consumer |
