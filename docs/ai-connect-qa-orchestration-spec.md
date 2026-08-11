# AI Connect — RAG QA 구현 명세

---

## 0. E2E 흐름 (엔드포인트)

```
[TX-A — 클라이언트 QA 트랜잭션]
  X-Transaction-Id: 클라이언트가 생성·전파 (Gateway → RAG → AI Gateway → RAG → Client)

Client
    │  POST /api/rag/{aiServiceName}/qa          ← 클라이언트 QA 진입
    │  Headers: X-API-Key, X-User-No, X-Transaction-Id
    │  Body: sessionId, messageId, content, categoryId?, documentId?, modelCode?
    ↓
  Gateway :9002  /api/rag/**
    ↓
RAG Storage Service  (RagQaService)
    │
    │  ┌──────────────────────────────────────────────────────────────────────────┐
    │  │ [TX-C — 임베딩 트랜잭션] retrieve 1단계 (RAG 내부)                         │
    │  │   X-Transaction-Id: RAG가 embedTx UUID 신규 생성 (TX-A와 분리)             │
    │  │                                                                          │
    │  │ RAG Storage Service                                                      │
    │  │     │  POST /api/ai/{embeddingAiServiceName}/embedding/request           │
    │  │     │  Body: { "inputs": [{ "id": "query", "text": "<content>" }] }      │
    │  │     ↓                                                                    │
    │  │   Gateway :9002  /api/ai/**                                              │
    │  │     ↓                                                                    │
    │  │   AI Gateway  →  Embedding Model  →  AI Gateway                          │
    │  │     ↓                                                                    │
    │  │   Gateway  →  RAG                                                        │
    │  │ RAG ← 쿼리 벡터 수신                                 [TX-C 종료]            │
    │  └──────────────────────────────────────────────────────────────────────────┘
    │
    ├─ Qdrant  POST /collections/{collection}/points/search   (RAG → Qdrant 직접)
    ├─ RagCitationEnricher  (sourceUri·page 등 DB 보강)
    └─ citations[] 구성 완료
    │
    │  POST /api/ai/{aiServiceName}/qa                         ← [TX-A] RAG → Gateway → AIG
    │  Headers: X-API-Key, X-User-No, X-Transaction-Id (TX-A 유지)
    │  Body: sessionId, messageId, content, modelCode?, citations[]
    │  ※ rag.gateway.base-url (기본 :9002) — Gateway 경유
    ↓
  Gateway :9002  /api/ai/**
    ↓
AI Gateway  (QaOrchestrationService)
    │
    │  ┌──────────────────────────────────────────────────────────────────────────┐
    │  │ [TX-B — LangChain 트랜잭션]                                              │
    │  │   X-Transaction-Id: AI Gateway가 langchainTx UUID 신규 생성              │
    │  │                                                                          │
    │  │ AI Gateway  →  LangChain Service                                         │
    │  │     GET /api/conversation/{sessionId}/context                            │
    │  │     ├─ select_qa_history_context (토큰 예산) → messages·orphan questions   │
    │  │     └─ reconcile_session_storage — Redis overflow trim              [TX-B] │
    │  └──────────────────────────────────────────────────────────────────────────┘
    │
    ├─ LLM  (AIG 내부 chat/stream 파이프라인, citations + 히스토리)  [TX-A]
    ├─ AIG  Redis append  session:{sessionId}:messages | :questions  [TX-A]
    └─ RAG ← 200 { messageId, sessionId, answer, modelName, provider, citations }
    │         ※ citations는 AIG 최종 결과 (no-context 재시도 시 비울 수 있음)
    ↓
RAG Storage Service  (동일 TX-A)
    ├─ 응답 citations[] 기준 rag_qa_citation INSERT  (내부 — 별도 HTTP 호출 없음)
    ↓
  Gateway :9002
    ↓
Client ← 200 { messageId, sessionId, answer, modelName, provider, citations }  [TX-A 종료]
```

| 구간 | HTTP | 비고 |
|------|------|------|
| 클라이언트 QA | `POST /api/rag/{aiServiceName}/qa` | Gateway `:9002` 경유. RAG가 retrieve → AIG `/qa` → citation DB 저장 후 응답 |
| 벡터 검색만 (단독) | `POST /api/rag/{aiServiceName}/retrieve` | citations만 반환. QA E2E의 retrieve 1단계와 동일 로직 |
| 임베딩 [TX-C] | `POST /api/ai/{embeddingAiServiceName}/embedding/request` | Gateway `:9002` 경유. `rag.embedding.ai-service-name` (기본 `openai`) |
| Qdrant | `POST /collections/{collection}/points/search` | Gateway 미경유, RAG → Qdrant 직접 |
| QA 오케스트레이션 [TX-A] | `POST /api/ai/{aiServiceName}/qa` | Gateway `:9002` 경유 (`rag.gateway.base-url`). citations + sessionId + content |
| LangChain context [TX-B] | `GET /api/conversation/{sessionId}/context` | 토큰 예산 선택 + Redis overflow trim. `messages`=Q&A 쌍, `questions`=orphan |
| Redis turn append [TX-A] | AIG **내부** (`ConversationSessionRedisService`) | RPUSH만. trim은 다음 GET /context 시 LangChain |
| LLM [TX-A] | AIG 내부 `chat/stream` 파이프라인 | 외부 HTTP 노출 없음 |
| citation DB [TX-A] | RAG **내부** (`RagQaCitationService`) | AIG 응답 `citations[]` 저장. `rag_qa_log` 없음 |
| citation 조회 | `GET /api/rag/{aiServiceName}/qa/messages/{messageId}` | messageId 기준 |

> **구현 상태:** 클라이언트 `/qa`, retrieve(내부), AIG `/qa`, LangChain `/context`(토큰 선택·Redis trim), AIG Redis append(RPUSH), RAG citation 내부 저장 ✅

---

## 1. RAG → AI Gateway

RAG `POST /qa` 처리 중 `RagRetrieveService`로 citations를 얻은 뒤, **Gateway**를 경유해 AIG `/qa`를 호출한다 (`rag.gateway.base-url`).

**`POST /api/ai/{aiServiceName}/qa`**

헤더: `X-API-Key`, `X-User-No`, `X-Transaction-Id`

```json
// request
{
  "sessionId": "채팅방-uuid",
  "messageId": "메시지-uuid",
  "content": "환불 정책 알려줘",
  "modelCode": "gpt-4o-mini",
  "citations": [
    {
      "chunkId": "string",
      "documentId": "string",
      "score": 0.91,
      "page": 3,
      "sourceUri": "s3://...",
      "snippet": "..."
    }
  ]
}
```

AI Gateway 내부 처리:

1. LangChain `GET /api/conversation/{sessionId}/context` → 토큰 예산 맥락 + Redis overflow trim `[TX-B]`
2. 선택된 맥락 + citations + `content` → LLM (AIG 내부 chat/stream 파이프라인) `[TX-A]`
3. AIG Redis append (RPUSH) — trim 없음 `[TX-A]`
4. **citation DB 저장은 AIG가 하지 않음** — 응답의 `citations[]`를 RAG가 수신 후 내부 저장 (§2)

```json
// response
{
  "messageId": "uuid",
  "sessionId": "채팅방-uuid",
  "answer": "답변",
  "modelName": "gpt-4o-mini",
  "provider": "openai",
  "citations": [
    {
      "chunkId": "string",
      "documentId": "string",
      "score": 0.91,
      "page": 3,
      "sourceUri": "s3://...",
      "snippet": "..."
    }
  ]
}
```

> `citations`는 AIG **최종** 결과이다. no-context 재시도 후 비워질 수 있으며, RAG는 이 값을 그대로 DB에 반영한다.

---

## 2. RAG citation DB 저장 (`rag_qa_citation`)

`rag_qa_log` 없음. **`POST /qa` 응답 직전** RAG가 내부에서 저장한다 (`RagQaCitationService`).

| 항목 | 내용 |
|------|------|
| 트리거 | `RagQaService.ask()` — AIG `/qa` 응답 수신 후 |
| 저장 대상 | 응답 `citations[]` (비어 있으면 저장 생략) |
| 보강 | `RagCitationEnricher` (sourceUri·page 등) |
| 공개 API | **없음** — 별도 `POST /qa/citations` 호출하지 않음 |

**저장 흐름 (의사코드)**

```
aiRes = aiQaClient.qa(...)
if aiRes.citations is not empty:
    ragQaCitationService.save(messageId, aiRes.citations)
return aiRes  → Client
```

**조회:** `GET /api/rag/{aiServiceName}/qa/messages/{messageId}`

---

## 3. Redis 저장 형식 (sessionId)

키: **`session:{sessionId}`**

### `session:{sessionId}:messages`

List · 항목 JSON 문자열

```json
{ "role": "user", "content": "그 기간 복직 조건은?", "metadata": {} }
```

```json
{
  "role": "assistant",
  "content": "복직 조건은 ...",
  "metadata": { "noContext": false }
}
```

noContext 답변:

```json
{
  "role": "assistant",
  "content": "제공된 문서에 이 질문에 답할 수 있는 정보가 충분하지 않습니다.",
  "metadata": { "noContext": true }
}
```

### `session:{sessionId}:questions`

List · 항목 JSON 문자열

```json
{ "content": "환불 정책 알려줘" }
```

```json
{ "content": "그 기간 복직 조건은?" }
```

---

## 4. Redis 샘플 (QA 2턴 후)

**`session:550e8400-e29b-41d4-a716-446655440000:questions`**

```json
{"content":"환불 정책 알려줘"}
{"content":"그 기간 복직 조건은?"}
```

**`session:550e8400-e29b-41d4-a716-446655440000:messages`**

```json
{"role":"user","content":"환불 정책 알려줘","metadata":{}}
{"role":"assistant","content":"환불은 30일 이내...","metadata":{"noContext":false}}
{"role":"user","content":"그 기간 복직 조건은?","metadata":{}}
{"role":"assistant","content":"복직 조건은 ...","metadata":{"noContext":false}}
```
