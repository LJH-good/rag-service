# 그래프 RAG 기능 리스트업 및 공수 산정

> 설계 전체는 [`docs/graph-rag-design.md`](./graph-rag-design.md) 참고. 이 문서는 그중 **공수 산정을 기능 단위로 리스트업**한 별도 문서다.
> 기준: 단위 **인일(MD)**.
> **담당 구조**: rag-storage·langchain-service·AI Gateway·admin 모두 동시에 진행하는 업무라 서비스별로 나누지 않고 **기능 단위로 묶어 하나의 공수**로 잡는다. 병행 트랙으로 나누지 않고 전부 하나의 합산 공수로 본다.

---

## 1. 완료된 기능

인덱싱(Pass1→청킹→Pass2) → 벡터+그래프 검색 → RRF fusion → QA 한 바퀴가 돌아가는 수준까지는 개발이 끝난 상태다.

| 기능 | 포함 범위 | 공수   | 상태 |
|---|---|------|---|
| 기본 1사이클 | Pass1(`EXTRACT_ENTITY`)·Pass2(`EXTRACT_RELATION`) 워커, 그래프 테이블·브리지, 관계형 traverse, RRF fusion, QA 오케스트레이션 최소 경로, langchain parse-clean/chunk 연동 | 3 MD | ✅ 완료 |

아래는 그 위에서 **품질·지연·비용을 맞추기 위한 잔여 기능**이다.

---

## 2. 잔여 기능 — rag + langchain + AI Gateway

### 2-1. 튜닝·검증 — 1.5 MD

`rag.graph` 설정과 프롬프트를 실문서·실질문으로 돌리며 조정. 신규 개발이 아니라 파라미터 튜닝.

- YAML/ENV 파라미터 실측 조정 (light/heavy 모델 분기 임계치, timeout, chunk-batch-size, max-chars-per-chunk, entity-link-ttl 등) — 대상 상세는 [부록](#부록-튜닝-대상-상세)
- 하드코딩 상수(`GRAPH_BUDGET_MS`, `RRF_K`, `GRAPH_EXTRA_CITATIONS`, `MAX_SEEDS`, `MAX_GRAPH_CHUNKS`, `SEED_WEIGHT`/`HOP_WEIGHT`) 실측 후 조정, 필요 시 외부화
- ✅ YAML↔코드 fallback 불일치 정리 — `chunk-batch-size` / `timeout-seconds` 실측 확정값(**8** / **900**)으로 `RagProperties` fallback·기본 Graph 객체 정렬 (2026-07-23)
- ✅ 하드코딩 확정: `MAX_SEEDS=20`, `MAX_GRAPH_CHUNKS=50` (`RagGraphTraversalService` + admin debug 정렬). `RRF_K`/`SEED_WEIGHT`/`HOP_WEIGHT`/`GRAPH_BUDGET_MS`/`GRAPH_EXTRA_CITATIONS`는 골든셋 추가 실측 후
- 프롬프트 vocabulary 반복 튜닝

### 2-2. 골든셋 구축 — 1 MD

품질을 숫자로 검증할 기준. 2-1과 병행.

- 연결형 질문 20~50문항 셋업 (실제 업무 문서 기반)
- 벡터-only 답변 vs 벡터+그래프 fusion 답변 비교 채점 기준
- 반복 실행 가능한 측정 스크립트/프로세스

### 2-3. 1단계 보완 — 1.5 MD (rag 1 + langchain 0.5)

기본 사이클 위 품질·안정화. langchain 쪽 Pass1/2 엔드포인트 정비가 여기 물려 있다.

- Pass1 실패 시 원본 폴백 청킹 로직 견고화
  — Pass1(LLM 호출)이 실패하면 정리본 없이 원본으로 청킹해 벡터 RAG는 살리는 분기가 있는데, 타임아웃·응답 파싱 오류·부분 실패 같은 다양한 실패 케이스에서 실제로 안전하게 걸리는지 검증이 안 된 상태라 이를 다진다.
- vocabulary 닫힌 세트 강제 (세트 밖 relation → `RELATED_TO` fallback 검증)
  — admin이 정한 Entity TYPE/RELATION 세트 밖의 값을 LLM이 만들어내도 지금은 걸러지지 않을 수 있다. 세트 밖 relation은 `RELATED_TO`로 강제 흡수하는 검증 로직을 넣어, 같은 개념이 여러 이름의 관계로 흩어져 그래프가 파편화되는 걸 막는다.
- 엔티티 정규화(동의어 병합) 로직 일원화
  — 인덱싱 시점(Pass1)의 엔티티 병합 규칙과 검색 시점(`entity:link` 캐시)의 정규화 규칙이 서로 다르면 그래프 탐색이 엇나간다. 두 시점이 같은 규칙을 쓰도록 통일한다.
- **[langchain]** Pass1·Pass2 엔드포인트(`parse-clean`/`chunk`, LLM 추출 호출) 테스트 코드 추가 — 현재 `RagGraphEntityWorker`·`RagGraphWorker` 관련 단위/통합 테스트 부재. 프롬프트나 DTO를 바꿨을 때 깨졌는지 확인할 방법이 없는 상태라 최소한 성공/실패 케이스를 검증하는 테스트를 붙인다.
- **[langchain]** Pass1·Pass2 LLM 추출 호출(`RagGraphExtractionClient` → AI Gateway `chat/stream`)에 `rag.graph.timeout-seconds`가 실제로 적용되는지 코드 추적, 안 걸리면 명시적으로 연결 — PCC는 `pcc.timeout-ms`가 명확히 적용되는데 Pass1/Pass2 쪽은 게이트웨이 기본값에 얹혀가는 건지 불분명하다.

### 2-4. 쿼리 결과 캐시 — 0.75 MD

반복 질문 latency 절감. 그래프 탐색(seed 찾기 + 1-hop + 가중치 계산)은 매번 다시 계산하기엔 비용이 있는 연산이라, 같은/비슷한 질문이 반복되면 결과를 재사용한다. `2-5 세션 문맥`과는 다른 캐시다 — 세션 문맥은 "이 대화에서 이미 resolve된 엔티티"를 기억해 follow-up을 가볍게 만드는 것이고, 이 캐시는 세션과 무관하게 "같은 질문이면 탐색을 다시 안 한다"는 결과 재사용이다.

- `graph:query:{hash}` 캐시 키 설계·구현
  — 해시에 뭘 넣을지가 아직 안 정해졌다. 질문 원문을 그대로 해시하면 "A회사 정책은?"과 "A회사 정책 알려줘"가 다른 키로 잡혀 캐시가 거의 안 맞는다. 정규화한 질문을 쓸지, resolve된 seed 엔티티 id 조합을 쓸지 정하고, 캐시 값에 traversal로 찾은 청크 id·스코어를 저장하는 구조를 설계한다.
- TTL·무효화 정책 정의
  — 문서가 삭제되거나 재인덱싱돼 관계가 바뀌면 관련 `graph:query:*` 캐시도 같이 지워져야 한다(6절 "정확한 삭제" 원칙과 연결). TTL만으로는 부족하고 삭제/재인덱싱 이벤트에 걸리는 명시적 무효화가 필요하다.
- 캐시 히트/미스 로깅
  — 캐시가 실제로 얼마나 맞는지 모르면 효과가 있는지 판단할 수 없다. 히트율이 낮으면 키 설계(정규화 방식)가 잘못됐다는 신호이므로, 로깅을 붙여 튜닝(2-1)에 참고한다.

### 2-5. 세션 문맥 — 1.75 MD (rag 1.25 + langchain 0.5)

follow-up 질문의 검색 연속성. langchain 쪽 세션 메모리 경계 정리가 여기 물려 있다.

- `rag:session:{id}:graph_context` 저장 스키마 확정
  — 활성 엔티티를 id로 저장할지 이름으로 저장할지, 직전 턴 traversal 결과를 같이 넣을지, 몇 턴까지 누적할지 등 실제 JSON 구조가 아직 안 정해졌다. 이게 확정돼야 아래 로직들을 짤 기준이 생긴다.
- 세션 내 활성 엔티티 갱신 로직
  — 매 턴 질문이 들어올 때마다 "지금 이 세션에서 뭐가 화제인지"를 최신 상태로 갱신한다. 예: 1턴 "A회사·B회사 비교" → 둘 다 활성화, 2턴 "A회사 예외 조건은?" → A회사는 유지하고 "예외조건"을 새로 추가.
- follow-up 질문에서 1-hop 재활용 판단 로직
  — 가장 애매한 부분. "이번 질문은 이미 활성화된 엔티티에서 1-hop만 타면 충분하다"를 언제 판단할지 기준이 필요하다(예: 새 엔티티 없이 "그 예외는?"처럼 기존 활성 엔티티를 가리키는 대명사/생략 표현일 때). 오판하면 필요한 전체 재검색을 건너뛰어 답이 부실해지거나, 반대로 계속 못 믿고 매번 전체 탐색해 캐시 의미가 없어진다.
- 24h 미러 만료·미스 시 전체 재검색 폴백 검증
  — `graph_context`는 원본이 아니라 캐시로 취급된다(세션 24h 미러). 만료되거나 어떤 이유로든 비어 있으면 이전 맥락 없이 처음부터 전체 그래프 탐색으로 안전하게 돌아가야 하는데, 이 폴백이 실제로 안 깨지는지 검증한다.
- **[langchain]** langchain Redis(대화 원문) ↔ RAG Redis(`graph_context`) 경계 유지, `sessionId`만 공유하고 데이터 왕복 없음을 보장
  — 두 서비스가 각자 저장소를 소유하기로 설계돼 있는데(langchain=대화 원문, RAG=검색 문맥), 실제 구현에서 한쪽이 다른 쪽 Redis를 직접 읽는 식으로 경계가 새지 않았는지 점검한다.

### 2-6. 질문 프로파일 → AIG 라우팅 — 2 MD (rag 1 + AI Gateway 1)

후순위·비용 최적화 레이어. AI Gateway 쪽 라우팅 반영이 여기 물려 있다.

- 검색 부산물(관련 개념 수, hop 수, 근거 건수)로 질문 프로파일 산출
- 프로파일 DTO 설계 및 AIG 전달 스펙 정의
- **[AI Gateway]** `fitScore` 계산 로직, `RouteSelectionService`에 `finalScore = (1-w)·perfScore + w·fitScore` 반영
- **[AI Gateway]** 프로파일 없을 때 기존 지연+비용 라우팅으로 폴백(하위 호환)

### 2-7. 안정화·E2E — 1.5 MD (rag + langchain + AI Gateway 통합 검증)

- 그래프 탐색 타임아웃/폴백 동작 검증
- 문서 삭제 시 그래프·캐시·정리본 연쇄 삭제 검증
- `userNo`/`categoryId` 권한 스코프 강제 검증
- 업로드→QA→follow-up 통합 시나리오 확인
- **[langchain]** ragQa citation 프롬프트·SSE 스트리밍 검증
- **[AI Gateway]** Pass1 문서 용량 기반 모델 라우팅(인덱싱 시점) 동작 확인

### 2-8. 의미 청킹(semantic chunking) — 2 MD (langchain 1.5 + rag 0.5)

지금 CHUNK는 `rag.chunk`(max-chars/overlap-chars/min-chars)로 글자수만 보고 자른다(`RagPccWorker`/`RagGraphEntityWorker` → `LangchainPccClient.chunk()`). 문장·문단 경계나 의미 유사도는 보지 않아, 한 주제가 청크 경계에서 끊기는 경우가 생긴다. 이를 임베딩 유사도 기반 경계 탐지로 보완한다.

- **[langchain]** 문장 단위 임베딩 후 인접 문장 간 유사도가 떨어지는 지점을 청크 경계로 판단하는 로직 구현
- **[langchain]** 유사도 경계와 기존 max-chars 상한이 충돌할 때(경계 미검출·과대 청크) size 기반 강제 분할로 폴백
- 유사도 임계치·최소/최대 문장 수 등 파라미터 튜닝
- **[rag]** `PccChunkRequest`/`PccChunkHints`에 semantic 모드 플래그·관련 설정 추가, `application.yml` `rag.chunk`에 옵션 반영
- 골든셋(2-2) 대비 char 기반과 recall/citation 품질 비교 검증

### 소계 (rag + langchain + AI Gateway)

| # | 기능 | 공수 |
|---|---|---|
| 2-1 | 튜닝·검증 | 1.5 MD |
| 2-2 | 골든셋 구축 | 1 MD |
| 2-3 | 1단계 보완 (+langchain) | 1.5 MD |
| 2-4 | 쿼리 결과 캐시 | 0.75 MD |
| 2-5 | 세션 문맥 (+langchain) | 1.75 MD |
| 2-6 | 질문 프로파일 → AIG 라우팅 (+AI Gateway) | 2 MD |
| 2-7 | 안정화·E2E (+langchain +AI Gateway) | 1.5 MD |
| 2-8 | 의미 청킹 (+langchain) | 2 MD |
| **소계** | | **12 MD** |

---

## 3. 잔여 기능 — admin

### 3-1. admin 어휘 관리 — 1.75 MD

> **소스**: Java enum(`RagEntityType`/`RagRelationType`) → **DB `rag_graph_vocab_entry`** (builtin seed + CRUD). enum 클래스 제거됨.

- ✅ **조회 API** — `GET /api/rag/admin/graph/vocabulary` (DB 어휘 소스)
- ✅ **CRUD API** — TYPE/RELATION 추가·활성/비활성·soft-delete  
  (`POST|PUT|DELETE /api/rag/admin/graph/vocabulary/types|relations`)
- 세트 관리 화면 (동결/활성 상태 표시 포함) — 프론트엔드 잔여
- 초기 세트 부트스트랩 검수 플로우 (샘플 20~50문서 open extraction 빈도표 확인) — 잔여  
  (업로드 엔티티 검수로 builtin 확정은 2026-07-24 진행, open extraction 빈도표 플로우는 후순위)

### 3-2. 그래프 디버그 화면 보강 — 2 MD

`AdminGraphController`(rag-storage) + `AdminRagGraphController`(bo-backend) + `KnowledgeGraphTestPage.tsx`(admin-front)로 QA 비교·문서 그래프 스냅샷·traverse 디버그는 이미 동작한다. 여기서 빠진 걸 채우는 항목.

- 엔티티/관계 **목록 조회 화면** (TYPE/RELATION 필터, 페이징) — 지금은 문서 단위 스냅샷만 있고, "TYPE이 X인 엔티티 전체" 같은 횡단 조회가 없음
- **그래프 시각화**(노드-엣지 렌더링) — 지금 API는 JSON 데이터만 반환, traverse 디버그 결과를 그림으로 보는 화면이 없어 로그성 데이터를 그대로 읽어야 함
- 여러 문서를 가로지르는 그래프 탐색 뷰 — 지금은 단일 문서 범위로 제한
- 엔티티/관계 **검수·수정(삭제, 신뢰도 조정) UI** 및 이를 위한 rag-storage 쪽 소규모 mutation API 추가 — 지금은 조회만 되고 고칠 방법이 없음

### 소계 (admin)

| # | 기능 | 공수 |
|---|---|---|
| 3-1 | admin 어휘 관리 | 1.75 MD |
| 3-2 | 그래프 디버그 화면 보강 | 2 MD |
| **소계** | | **3.75 MD** |

---

## 4. 합계 및 일정

admin도 병행 담당자가 아니라 같이 진행해야 하는 업무라, 별도 트랙으로 나누지 않고 rag+langchain+AI Gateway(12 MD) + admin(3.75 MD)을 그대로 더한 **하나의 숫자**로 잡는다.

| | 공수 |
|---|---|
| 완료(기본 1사이클) | 3 MD |
| **잔여 (rag+langchain+AI Gateway+admin)** | **15.75 MD ≈ 약 3.15주** |

**우선순위**

`2-1 튜닝·검증(+2-2 골든셋 병행) → 3-1 admin 어휘 관리 → 2-3 1단계 보완 → 2-8 의미 청킹 → 2-5 세션 문맥 → 2-4 쿼리 캐시 → 3-2 그래프 디버그 화면 보강 → 2-7 안정화·E2E → 2-6 질문 프로파일(AIG fitScore)`

admin 어휘(3-1)는 Pass1/Pass2 프롬프트에 주입되는 값이라 1단계 보완 전에 먼저 확정해야 하고, 그래프 디버그 화면(3-2)은 안정화·E2E 검증과 맞물려 뒤쪽에 둔다. 사이클 검증·튜닝과 골든셋을 먼저 두고, 비용 레이어(2-6 AIG fitScore)는 검증 후로 미룬다.

> 가정: 인프라(PostgreSQL·Redis·Qdrant·MinIO)와 기존 벡터 RAG·채팅 파이프라인은 이미 운영 중. 신규 그래프 DB(AGE 등) 도입·전량 재인덱싱·다국어 vocabulary는 본 산정에 포함하지 않음.
> 이번 산정은 요청에 따라 직전 산정 대비 공수를 전체적으로 50% 축소했다. 실제 구현 난이도가 그대로라면 축소분만큼 일정 리스크가 커지니, 진행하며 실측치로 재조정 권장.

---

## 부록: 튜닝 대상 상세

기능 2-1(튜닝·검증)에서 실제로 손대는 대상 목록.

### ① YAML/ENV로 조정 (`rag.graph`)

`application-*-consumer.yml` (API는 `enabled`만)

| 키 / ENV | 기본 | 용도 |
|---|---|---|
| `enabled` / `RAG_GRAPH_ENABLED` | false | Pass1/2·검색 RRF 게이트 |
| `light-ai-service-name` | openai | Pass1·Pass2 LLM |
| `heavy-ai-service-name` | claude | 〃 |
| `size-threshold-bytes` | 1048576 (1MB) | light/heavy 분기 |
| `light`/`heavy-model-code` | 빈값 | 있으면 preference 무시 |
| `timeout-seconds` | **900** (확정) | gatewayGraphWebClient / Pass1·2 |
| `chunk-batch-size` | **8** (확정) | Pass2 LLM 배치 |
| `max-chars-per-chunk` | 4000 | Pass2 프롬프트 절단 |
| `entity-link-ttl-seconds` | 604800 (7일) | Redis `entity:link` |

인프라: Pass1 캐시용 consumer Redis `192.168.0.100:6379/0`.

### ② 코드 하드코딩 (변경 시 재빌드)

| 위치 | 상수 | 기본 | 의미 |
|---|---|---|---|
| `RagRetrieveService` | `GRAPH_BUDGET_MS` | 800 | 그래프 traverse 타임아웃 (미확정) |
| | `RRF_K` | 60 | RRF 상수 (미확정) |
| | `GRAPH_EXTRA_CITATIONS` | 3 | 벡터 topK 밖 그래프 전용 citation 상한 (미확정) |
| `RagGraphTraversalService` | `MAX_SEEDS` | **20** ✅ | 쿼리 매칭 seed 상한 |
| | `MAX_GRAPH_CHUNKS` | **50** ✅ | 그래프 회수 청크 상한 |
| | `SEED_WEIGHT` / `HOP_WEIGHT` | 2.0 / 1.0 | seed·1-hop 가중 (미확정) |
| | hop 깊이 | 1-hop 고정 | multi-hop 없음 |
| `RagGraphVocabularyService` | TYPE 9 · RELATION 9 · fallback | ✅ DB `rag_graph_vocab_entry` (구 Java enum 제거) | TYPE→`CONCEPT`, RELATION→`RELATED_TO` |
| `GraphPromptTexts` | 프롬프트 본문(어휘는 DB에서 주입) | 현행 | 골든셋 품질 반복 튜닝 잔여 |
