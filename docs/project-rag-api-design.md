# 포탈 '프로젝트' 기능 — RAG 연동 API 설계

> 포탈에 **프로젝트**(참고 자료를 모아두는 작업 공간) 기능이 추가됨에 따라, RAG가 새로 받아야 하는 요청과 처리 방식을 정리한 설계.
> 관련 문서: [`graph-rag-design.md`](./graph-rag-design.md) (그래프 RAG 본 설계), [`api-spec.md`](./api-spec.md) (기존 API 명세)

---

## 한 줄 요약

사용자가 포탈에서 프로젝트를 만들고 참고 파일(문서·이미지)을 올리면, **포탈이 그 파일을 LLM으로 요약한 summary 텍스트**를 projectId와 함께 RAG에 넘긴다. **RAG는 파일을 받지 않는다** — summary 텍스트를 원문 삼아 벡터/그래프에 적재하고, 이후 그 프로젝트의 채팅방에서 질문이 오면 **projectId 스코프로만 검색해 citation만 돌려준다.** 답변 생성(LLM 호출)은 citation을 받은 **포탈이 알아서 처리**한다.

---

## 1. 기능 개요

```
[포탈]                                            [RAG]
프로젝트 생성 ──(프로젝트 마스터는 포탈 소유)──      (관여 없음)
   │
   ├─ 참고 파일 업로드 (파일 보관·처리는 포탈)
   │     │
   │     └─ LLM 요약 → summary 생성
   │          │
   │          └──(summary + projectId + fileId)──► 텍스트 적재
   │              (파일 없음)                        (청킹·임베딩·그래프)
   │
   └─ 채팅방 생성 (projectId 1 : N sessionId)
        │
        └─ 질의 ──(searchQuery + projectId)────────► project 스코프 검색
                                                      (벡터 + 그래프 fusion)
                  ◄──── citations (fileId 포함) ──────┘
        │
        └─ 답변 생성: 포탈이 citation의 fileId로 원본 파일을 내려받아
           LLM 호출 등 직접 처리 (RAG 관여 없음)
```

- **프로젝트**: 사용자가 만든 작업 공간. 참고 파일들과 채팅방들이 이 밑에 묶인다.
- **summary**: 사용자가 쓴 설명이 아니라, **포탈이 파일을 LLM으로 요약한 정리본 텍스트**. RAG 입장에서 이 텍스트가 곧 적재할 원문이다.
- **채팅방(sessionId)**: 프로젝트 하위에 여러 개 생성. RAG 입장에서는 기존과 동일하게 세션 문맥(graph_context) 키로만 쓰고, 프로젝트와의 부모-자식 관계는 포탈이 관리한다.
- **검색 범위**: 질문이 오면 해당 **프로젝트에 적재된 summary들만** 대상으로 벡터 + 그래프 검색을 수행한다.
- **RAG의 역할은 검색기까지**: RAG는 citations를 리턴하면 끝. 답변 생성·표시·대화 저장은 포탈 몫이라, 기존 QA 오케스트레이션(`/qa`) 경로는 프로젝트 기능에서 사용하지 않는다.
- **citation에는 fileId가 실린다**: 어떤 파일의 summary에서 뽑은 근거인지 알 수 있도록, 적재 때 받은 **포탈 파일 ID를 citation에 그대로 되돌려준다**. 포탈은 이 fileId로 원본 파일을 내려받아 LLM에 넣는 등 후속 처리를 한다.

---

## 2. 역할 분담 — 무엇이 어디 소유인가

| 데이터 | 소유 | 비고 |
|---|---|---|
| 프로젝트 마스터(이름·설명·멤버·채팅방 목록) | **포탈** | RAG는 모름 — RAG RDB에 프로젝트 테이블을 만들지 않는다 |
| 원본 파일(보관·표시·다운로드) | **포탈** | **RAG는 파일 자체를 받지도, 저장하지도 않는다** (MinIO 원본 저장 없음) |
| summary 생성(파일 → LLM 요약) | **포탈** | RAG는 결과 텍스트만 수신 |
| summary 적재분(청크·임베딩·그래프) | **RAG** | 기존 파이프라인 재사용 + 약간의 변경(3.3 참고) |
| 파일 ID(fileId) | 포탈이 생성·전달 | RAG는 **불투명한 값으로 pass-through** — 적재 때 받아 뒀다가 citation에 그대로 되돌려준다. 포탈이 이 값으로 원본 파일을 찾아 후속 처리(다운로드→LLM 호출 등) |
| projectId | 포탈이 생성·전달 | RAG는 **불투명한 스코프 키**로만 취급. 검증·해석하지 않고 적재·필터에만 사용 |

> 원칙: RAG는 projectId를 **categoryId와 같은 급의 스코프 키**로만 다룬다. 프로젝트가 뭔지(이름·권한·상태)는 포탈 책임이고, "이 projectId로 적재해라 / 이 projectId 안에서만 찾아라"만 수행한다. **프로젝트 접근 권한 검증은 포탈에서 끝내고 온다** — RAG는 `project_id` 필터만 강제하고 userNo로 추가 제한하지 않는다(프로젝트는 멤버 공유 공간일 수 있어 업로더 본인만으로 좁히면 안 됨).

---

## 3. 적재 흐름 — summary 텍스트가 곧 원문

포탈이 **summary(LLM 요약 정리본) + projectId**를 보내면, RAG는 이 텍스트를 원문 삼아 기존 파이프라인에 태운다. **파일이 없으므로 파싱(PARSE) 단계가 필요 없다** — 텍스트가 바로 진입한다.

```
적재 요청 (summary 텍스트, projectId, fileId)   ※ 파일 없음 → PARSE 불필요
  │
  ▼
CLEAN → EXTRACT_ENTITY(Pass1) → CHUNK → EMBED → UPSERT → EXTRACT_RELATION(Pass2)
                                                   │
                                                   └─ Qdrant payload에 project_id 저장
```

### 3.1 summary 텍스트의 취급

- summary가 **적재 원문**이다. 청킹·임베딩·그래프 추출 모두 이 텍스트를 대상으로 한다. `page_content`(citation에 보여줄 텍스트)도 summary의 청크다.
- summary는 이미 LLM이 정리한 텍스트라 대체로 짧고 구조적 → 청크 수가 적고(1~수 개), Pass1(엔티티 사전+정리본)의 입력 부담도 작다. 문서 용량 기반 모델 라우팅상 대부분 **경량 모델 구간**에 떨어진다.
- 그래프(Pass1·Pass2)는 변경 없이 동작한다. 엔티티·관계 추출은 문서 단위라 입력이 summary여도 그대로다. 프로젝트는 **검색 시점의 스코프**로만 작용한다.

### 3.2 projectId — Qdrant payload 필터 키로 저장

- 모든 청크 payload에 `project_id`를 넣는다 (기존 `category_id`·`user_no`·`document_id`와 나란히).
- Qdrant에 `project_id` **payload 인덱스**를 생성해 필터 검색 성능을 확보한다.
- RAG RDB에 프로젝트 테이블은 만들지 않는다. 다만 파이프라인이 문서·Job 단위로 도는 구조라, summary 적재분도 **기존 문서·Job 레코드를 재사용**하고 거기에 `project_id` 꼬리표 컬럼을 추가한다. 이유: ① 프로젝트 삭제 시 대상을 찾아 연쇄 삭제 ② 그래프 탐색 스코프 계산에 문서 → 프로젝트 매핑 필요 ③ 비동기 파이프라인 상태 폴링.

### 3.3 기존 파이프라인 대비 변경점 요약

| 단계 | 변경 |
|---|---|
| 진입 | 파일 업로드 대신 **텍스트(JSON) 적재 API** 신규 — PARSE 없이 텍스트로 파이프라인 진입 |
| MinIO | **원본 파일 저장 없음** (청크 텍스트 저장 체계는 기존 그대로 재사용) |
| 문서 테이블 | `project_id` 컬럼 추가 (프로젝트 마스터 아님 — 소속 꼬리표) |
| UPSERT | Qdrant payload에 `project_id` 추가 (3.2) |

CLEAN·Pass1·CHUNK·EMBED·Pass2, Job 폴링은 **무변경**.

---

## 4. 검색 흐름 — projectId 스코프 질의

채팅방에서 질문이 오면:

1. **벡터 검색** — Qdrant 필터를 `project_id = {projectId}`로 걸고 topK 청크 회수
2. **그래프 탐색** — 벡터가 회수한 문서들을 대상으로 엔티티 관계를 타고 추가 근거 수집 (기존 방식 그대로 — 그래프 탐색 스코프는 벡터 topK 문서 유지)
3. **fusion(RRF)** — 두 결과를 융합해 citations 확정
4. **citations를 그대로 리턴하고 끝** — 답변 생성(LLM 호출)·표시·대화 저장은 포탈이 처리. RAG는 답변을 만들지 않는다.

폴백은 기존과 동일: 그래프 실패·타임아웃 시 벡터 단독, graph_context 미스 시 전체 재검색.

---

## 5. API 설계

> 아래는 **포탈(호출자) 기준** 요청·응답 규약이다. Gateway 경유 시 경로는 동일하고, 헤더·바디만 그대로 전달하면 된다.

---

### 5.1 적재 — `POST /api/rag/{aiServiceName}/projects/documents` (**신규**)

summary 텍스트를 프로젝트 스코프로 적재한다. **파일 없음 · PARSE 스킵.**

#### 요청

| | |
|---|---|
| Method / Path | `POST /api/rag/{aiServiceName}/projects/documents` |
| Headers | `Content-Type: application/json` · `X-User-No` · `X-Transaction-Id` |
| Path | `aiServiceName` — 예: `openai` (기존 업로드와 동일 패턴) |

**Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `projectId` | string (UUID) | ✅ | 프로젝트 스코프 키 |
| `fileId` | string | ✅ | 포탈 파일 ID. RAG는 검증 없이 저장 후 citation에 그대로 환원 |
| `summary` | string | ✅ | 포탈 LLM 요약본 = 적재 원문 |

```json
{
  "projectId": "11111111-1111-1111-1111-111111111111",
  "fileId": "portal-file-abc-001",
  "summary": "이 문서는 프로젝트 A의 요구사항 정리본이다. …"
}
```

#### 응답 `200`

| 필드 | 타입 | 설명 |
|---|---|---|
| `jobId` | string (UUID) | 파이프라인 Job ID — 폴링에 사용 (5.4) |
| `transactionId` | string (UUID) | 요청 `X-Transaction-Id`와 동일 |

```json
{
  "jobId": "880e8400-e29b-41d4-a716-446655440000",
  "transactionId": "bf84c960-49a6-40f6-8897-65788589e4aa"
}
```

> RAG 내부 `documentId`는 **응답에 넣지 않는다**. 이후 식별은 전부 `fileId`로 한다.
> 적재 완료 여부는 5.4 Job 폴링으로 확인한다.

---

### 5.2 검색 — `POST /api/rag/{aiServiceName}/retrieve` (**수정**)

citations만 돌려준다. **답변(`answer`) 없음** — LLM 호출은 포탈 몫.

#### 요청

| | |
|---|---|
| Method / Path | `POST /api/rag/{aiServiceName}/retrieve` |
| Headers | `Content-Type: application/json` · `X-API-Key` · `X-User-No` · `X-Transaction-Id` |

**Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `searchQuery` | string | ✅ | 검색어 (보통 현재 질문) |
| `projectId` | string (UUID) | 프로젝트 질의 시 ✅ | 있으면 `project_id` 단독 필터 (userNo 제한 없음) |
| `sessionId` | string (UUID) | ❌ | 채팅방 ID — graph_context 활용 |
| `graphEnabled` | boolean \| null | ❌ | 그래프 보강 on/off (`null`이면 서버 설정) |
| `categoryId` | string (UUID) \| null | ❌ | 프로젝트 질의에서는 미사용. `projectId`와 동시면 **projectId 우선** |
| `documentId` | string (UUID) \| null | ❌ | 특정 적재분으로 더 좁힐 때만 (프로젝트 흐름에서는 보통 미사용) |

```json
{
  "searchQuery": "이 프로젝트 요구사항에서 인증은 어떻게 돼?",
  "projectId": "11111111-1111-1111-1111-111111111111",
  "sessionId": "22222222-2222-2222-2222-222222222222",
  "graphEnabled": null
}
```

#### 응답 `200`

| 필드 | 타입 | 설명 |
|---|---|---|
| `citations` | array | 근거 목록. **`answer` 필드 없음** |
| `graphApplied` | boolean | 그래프 fusion 적용 여부 |
| `graphChunkCount` | int | 그래프에서 회수한 청크 수 |
| `graphOnlyPromotedCount` | int | 벡터 topK 밖에서 그래프만으로 올린 citation 수 |

**`citations[]` 항목 (프로젝트 스코프)**

| 필드 | 타입 | 설명 |
|---|---|---|
| `chunkId` | string | 청크/포인트 ID |
| `fileId` | string | **[신규]** 적재 때 받은 포탈 파일 ID |
| `score` | number | 유사도 점수 |
| `content` | string | **[신규]** 청크 **전체 텍스트** (`page_content` 원문) — 포탈이 LLM에 그대로 넘기기 위한 필드. `snippet`(미리보기 잘림)과 다름 |
| `snippet` | string | 미리보기용 잘린 텍스트 — 프로젝트 흐름에서는 `content`를 사용할 것 |
| `documentId` | string \| null | RAG 내부 ID — 프로젝트 질의에서는 **무시해도 됨** |
| `page` / `slide` / `sheet` | — | summary 적재분은 보통 `null` |
| `sourceUri` | string \| null | 원본 파일 URI 개념 없음 — 보통 `null` |

```json
{
  "citations": [
    {
      "chunkId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "fileId": "portal-file-abc-001",
      "score": 0.91,
      "content": "인증은 OAuth2 기반이며, 액세스 토큰 유효기간은 1시간이다. 리프레시 토큰은 30일간 유지된다. …(전체 청크 원문)",
      "snippet": "인증은 OAuth2 기반이며 …",
      "documentId": null,
      "page": null,
      "slide": null,
      "sheet": null,
      "sourceUri": null
    }
  ],
  "graphApplied": true,
  "graphChunkCount": 2,
  "graphOnlyPromotedCount": 0
}
```

> **`content` vs `snippet` 차이**: `snippet`은 기존 UI 미리보기용으로 잘린 텍스트, `content`는 Qdrant `page_content`의 전체 원문. 포탈은 LLM 컨텍스트 조립 시 `content`를 사용해야 한다.
> `projectId`가 없는 기존 카테고리 스코프 요청은 `content` 필드 없음(null) — 기존 langchain-service 호출자는 chunk_id 기반 내부 조회를 그대로 쓰므로 영향 없음.

---

### 5.3 삭제

#### 5.3.1 프로젝트 전체 — `DELETE /api/rag/projects/{projectId}` (**신규**)

프로젝트 삭제 시 해당 projectId 적재분 전부 연쇄 삭제.

##### 요청

| | |
|---|---|
| Method / Path | `DELETE /api/rag/projects/{projectId}` |
| Headers | `X-User-No` · `X-Transaction-Id` |
| Body | 없음 |

| Path | 설명 |
|---|---|
| `projectId` | 삭제할 프로젝트 스코프 키 |

```
DELETE /api/rag/projects/11111111-1111-1111-1111-111111111111
X-User-No: …
X-Transaction-Id: …
```

##### 응답 `204 No Content`

본문 없음. 성공 시 RDB·Qdrant·그래프·캐시까지 연쇄 삭제 완료(또는 비동기 시작 — 구현 시 동기 204로 맞춤).

---

#### 5.3.2 파일 1건 — `DELETE /api/rag/projects/{projectId}/files/{fileId}` (**신규**)

프로젝트에서 파일 제거 시 해당 적재분 1건만 삭제.

##### 요청

| | |
|---|---|
| Method / Path | `DELETE /api/rag/projects/{projectId}/files/{fileId}` |
| Headers | `X-User-No` · `X-Transaction-Id` |
| Body | 없음 |

| Path | 설명 |
|---|---|
| `projectId` | 프로젝트 스코프 |
| `fileId` | 포탈 파일 ID — RAG가 `projectId + fileId`로 내부 문서를 찾아 삭제 |

```
DELETE /api/rag/projects/11111111-1111-1111-1111-111111111111/files/portal-file-abc-001
X-User-No: …
X-Transaction-Id: …
```

##### 응답 `204 No Content`

본문 없음.

> 대상이 없으면 `404` (구현 시). 포탈은 RAG `documentId`를 몰라도 된다.

---

### 5.4 Job 폴링 — `GET /api/rag/pipeline/jobs/{jobId}` (**기존 재사용**)

5.1 적재 응답의 `jobId`로 처리 상태를 확인한다.

#### 요청

| | |
|---|---|
| Method / Path | `GET /api/rag/pipeline/jobs/{jobId}` |
| Headers | (기존 파이프라인과 동일) |
| Body | 없음 |

#### 응답 `200`

```json
{
  "jobId": "880e8400-e29b-41d4-a716-446655440000",
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "transactionId": "bf84c960-49a6-40f6-8897-65788589e4aa",
  "status": "RUNNING",
  "currentStep": "EMBED",
  "triggerType": "PROJECT_SUMMARY",
  "errorCode": null,
  "errorMessage": null,
  "startedAt": "2026-07-16T10:01:00Z",
  "endedAt": null,
  "createdAt": "2026-07-16T10:00:30Z",
  "progressPercent": 75,
  "terminal": false,
  "processingStatus": "PROCESSING"
}
```

| 필드 | 포탈이 볼 것 |
|---|---|
| `processingStatus` | `PROCESSING` / `SUCCEEDED` / `FAILED` |
| `terminal` | `true`이면 폴링 종료 |
| `progressPercent` | UI 진행률 |
| `documentId` | RAG 내부용 — **프로젝트 UI에서는 저장·표시 불필요** (`fileId`로 관리) |

> `jobId` 분실 시: `GET /api/rag/pipeline/transactions/{transactionId}/latest-job` (기존과 동일).

---

### 변경 요약

| API | 구분 | 요청 요약 | 응답 요약 |
|---|---|---|---|
| `POST /api/rag/{svc}/projects/documents` | **신규** | `{ projectId, fileId, summary }` | `{ jobId, transactionId }` |
| `POST /api/rag/{svc}/retrieve` | 수정 | `{ searchQuery, projectId?, sessionId?, … }` | `{ citations[{ fileId, content(전체), snippet(미리보기), … }], graph* }` — **answer 없음** |
| `DELETE /api/rag/projects/{projectId}` | **신규** | path만 | `204` |
| `DELETE /api/rag/projects/{projectId}/files/{fileId}` | **신규** | path만 | `204` |
| `GET /api/rag/pipeline/jobs/{jobId}` | 재사용 | path만 | Job 상태 (`processingStatus`, `terminal`, …) |
| `POST /api/rag/{svc}/documents/upload` | 무변경 | (기존) | (기존) |
| `POST /api/rag/{svc}/qa` | 미사용 | — | 프로젝트 기능에서 안 씀 |

---

## 6. 만들 때 신경 써야 할 점

| 항목 | 걱정 | 대응 |
|---|---|---|
| **summary 품질 전면 종속** | 검색 품질이 포탈 요약 품질에 100% 종속 — 원문이 RAG에 없어 요약에서 빠진 내용은 **영원히 검색 불가** | 요약 프롬프트·상세도는 포탈 몫임을 경계로 명시. 품질 개선이 필요하면 포탈이 summary를 다시 만들어 **재적재**(삭제 후 신규 적재)해야 함 |
| **citation의 성격** | 근거가 원문이 아니라 **요약본의 조각** — 원본 페이지/슬라이드 위치 개념이 없음 | citation은 "어느 파일(fileId)의 요약 근거"까지만. 원문 위치 환원은 하지 않음 (그래프 RAG 본 설계의 document 수준 citation 결정과 일관) |
| **이미지 파일** | 이미지는 파싱할 본문이 없음 | 해소됨 — **포탈 LLM 요약이 이미지도 텍스트화**해서 보냄. RAG는 텍스트만 다루므로 OCR·캡셔닝 불필요 |
| **스코프 격리** | 다른 프로젝트 자료가 섞여 검색되면 안 됨 | 검색 전 구간 `project_id` 필터 강제. "누가 이 프로젝트를 볼 수 있는가"는 포탈의 프로젝트 권한 검증이 담당(RAG는 userNo로 좁히지 않음 — 멤버 공유 프로젝트 대응) |
| **정확한 삭제** | 프로젝트 삭제 후 잔재(벡터·그래프·캐시)가 남으면 오답 근거로 등장 | 5.3의 연쇄 삭제 경로 하나로 일원화. 문서 테이블의 projectId 꼬리표가 삭제 대상 조회 기준 |
| **하위 호환** | 기존 업로드·검색이 깨지면 안 됨 | 기존 업로드는 무변경, retrieve의 projectId는 **선택 값**. 없으면 기존 카테고리 스코프 동작 그대로 |
| **summary 크기 한도** | 텍스트 적재라 업로드 용량 가드(20MB multipart)와 별개의 한도 필요 | 요청 본문 summary 최대 길이 제한 신설 — 임계치는 미확정 |

---

## 7. 공수 산정

> 기준: **1인 개발** · 단위 **인일(MD)**.
> 범위: **rag-storage-service** (포탈의 summary 생성·원본 파일·답변 LLM·채팅 UI는 **제외**).
> 전제: 기존 벡터/그래프 파이프라인(CLEAN→Pass1→CHUNK→EMBED→Pass2)·`/retrieve`·Job 폴링은 이미 동작 중. 그래프 RAG 본 설계([`graph-rag-design.md`](./graph-rag-design.md))의 잔여 공수와 **별도**다.

### 작업 단위

| 구분 | 내용 | 공수 | 비고 |
|---|---|---|---|
| **1. 스키마·인덱스** | `rag_documents`에 `project_id`·`file_id`(nullable) + 인덱스, Qdrant `project_id` payload 인덱스, UPSERT 시 payload 기록 | **0.5 MD** | 프로젝트 마스터 테이블 없음 |
| **2. 텍스트 적재 API** | `POST …/projects/documents` DTO·컨트롤러, `PROJECT_SUMMARY` 트리거(PARSE 스킵→CLEAN 진입), summary 텍스트 저장, 응답 `jobId`만 | **1 MD** | 기존 Job·워커 재사용 |
| **3. retrieve 확장** | 요청 `projectId`·`sessionId`·`graphEnabled`, `project_id` 단독 필터(userNo 미제한), citation에 `fileId` 환원 | **1 MD** | `/qa` 미사용 · 하위호환 유지 |
| **4. 삭제 API** | `DELETE …/projects/{projectId}`·`/files/{fileId}`, RDB·Qdrant·그래프·캐시 연쇄 삭제 | **0.5 MD** | 기존 문서 삭제 로직 재사용 |
| **5. 안정화·E2E** | 스코프 격리 검증, 재적재 정책·summary 한도·동시 전달 정책 확정, 적재→폴링→검색→삭제 한 바퀴 | **1 MD** | 6절 신경 쓸 점 + 부록 미확정 정리 |

### 합계

| | 공수 |
|---|---|
| **전체 (RAG)** | **4 MD** (≈ **1주** / 1인 기준) |

**우선순위 제안:**
`1 스키마 → 2 적재 → 3 retrieve → 4 삭제 → 5 E2E`.

> 가정: Gateway 라우팅·API Key는 기존 RAG API와 동일 패턴으로 붙이면 되고, **별도 langchain / AI Gateway 기능 개발은 본 산정에 넣지 않음**(프로젝트 질의는 citations-only retrieve, 답변 LLM은 포탈). 포탈 측(프로젝트 UI·파일·요약·채팅) 공수는 별도.

---

## 부록: 개발 세부 (미확정·구체화 후보)

- **적재 진입점**: 파일 파싱 없이 텍스트로 파이프라인 진입 — 신규 트리거 타입(예: `RagTriggerType.PROJECT_SUMMARY`) 추가, 첫 스테이지를 PARSE 대신 CLEAN(또는 텍스트 저장 후 CLEAN 대기)으로 생성. summary 텍스트는 기존 청크/원문 텍스트 저장 체계(MinIO 텍스트 키)를 재사용해 보관 — "원본 파일" 저장이 없다는 것이지 적재 텍스트 자체는 파이프라인 입력으로 저장됨.
- **Qdrant payload 추가 필드**: `project_id`(keyword, payload 인덱스 필수). 기존 `category_id`·`user_no`·`document_id`·`chunk_id`·`page_content`·`storage_key`와 나란히. (`page_content`가 곧 summary 청크라 별도 summary 필드는 불필요.)
- **RDB**: `rag_documents`에 `project_id`·`file_id` 컬럼(nullable) 추가 + `project_id` 인덱스. 프로젝트 마스터 테이블은 만들지 않음. `file_id`는 pass-through 값이라 문자열로 저장(포맷 검증 안 함).
- **fileId 환원 경로**: citation 매핑 시 documentId → `rag_documents.file_id` 조회로 보강(기존 `RagCitationEnricher`의 sourceUri 보강과 같은 지점). Qdrant payload에 `file_id`를 함께 넣으면 DB 조회 없이도 가능 — payload 저장 여부는 구현 시 확정.
- **API/DTO**: 신규 `ProjectDocumentIngestController`(또는 기존 컨트롤러 확장) — `{projectId, fileId, summary}` JSON 수신 → 문서·Job 생성, **응답은 jobId만**(documentId 비노출). `RetrieveRequest`에 `projectId`(UUID)·`sessionId`(UUID, graph_context용)·`graphEnabled`(Boolean) nullable 필드 추가. citation DTO에 `fileId`(nullable)·**`content`(nullable, 전체 청크 텍스트)** 추가 — `projectId` 있는 요청에만 채움, 기존 카테고리 스코프는 null. `AskRequest`(QA)는 변경 없음.
- **검색 필터**: `QdrantClient.buildTenantSearchFilter`에 projectId 분기 추가 — projectId 존재 시 `project_id` 단독 must 조건 (user_no·categoryId 분기와 배타, userNo 제한 없음).
- **삭제**: `DELETE /api/rag/projects/{projectId}`(전체)·`/files/{fileId}`(개별) 컨트롤러 신규 — 개별 삭제는 `project_id + file_id`로 내부 문서 조회 후 기존 삭제 로직 재사용, 전체 삭제는 `project_id` 기준 Qdrant filter delete + 문서별 루프.
- **미확정**: summary 최대 길이 한도, projectId·categoryId 동시 전달 시 정책(우선 vs 400), 프로젝트 적재분의 categoryId 취급(개인 카테고리 고정 vs 미사용), fileId의 Qdrant payload 저장 여부(vs DB 조회 보강만), **같은 fileId 재적재 시 정책**(replace로 갱신 vs 중복 에러 — `(project_id, file_id)` 유니크 여부), Pass1 스킵 여부(요약본이 이미 정리된 텍스트라 Pass1 정리본 생성이 중복일 수 있음 — 엔티티 사전만 뽑는 경량 모드 검토).
