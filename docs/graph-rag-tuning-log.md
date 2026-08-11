# Graph RAG 파라미터 튜닝 로그

> 실측 결과와 조정 근거를 파라미터 단위로 누적. 날짜별 섹션을 아래로 추가한다.

---

## 파라미터 현황 요약

> 실측 후 값이 바뀔 때마다 이 표를 업데이트한다.

### YAML/ENV (`rag.graph.*`)

| 파라미터 | 설명 | 기본값 | 현재 적용값 | 상태 | 최종 조정일 |
|---|---|---|---|---|---|
| `timeout-seconds` | Pass1·Pass2 LLM 호출(게이트웨이) 타임아웃(초) | 120 | **900** | ✅ 확정 | 2026-07-23 |
| `chunk-batch-size` | Pass2 관계 추출 시 한 번에 LLM에 넣을 청크 개수 | 4 | **8** | ✅ 확정 | 2026-07-23 |
| `max-chars-per-chunk` | Pass2 프롬프트에 넣는 청크 텍스트 절단 상한(자) | 4000 | 4000 | 🔲 미측정 | — |
| `size-threshold-bytes` | 문서 용량 기준 light/heavy 모델 분기 임계치 | 1,048,576 (1MB) | 1,048,576 | 🔲 미측정 | — |
| `entity-link-ttl-seconds` | 검색용 Redis `entity:link` 캐시 TTL | 604,800 (7일) | 604,800 | 🔲 미측정 | — |
| `light-ai-service-name` | 소형 문서용 Pass1·Pass2 LLM 서비스명 | openai | openai | ✅ 선정 확정 (별도 A/B 불필요) | 2026-07-24 |
| `heavy-ai-service-name` | 대형 문서용 Pass1·Pass2 LLM 서비스명 | claude | claude | ✅ 선정 확정 (별도 A/B 불필요) | 2026-07-24 |

> `light`/`heavy` 서비스명은 문서 용량별 최적화 대상 서비스를 이미 골라 넣은 값이라, 튜닝 로그에서 별도 검증·A/B 대상으로 두지 않는다. 바꿀 일은 게이트웨이 쪽 서비스 교체 시에만 해당.

### 코드 하드코딩

| 위치 | 상수 | 설명 | 기본값 | 현재 적용값 | 상태 | 최종 조정일 |
|---|---|---|---|---|---|---|
| `RagRetrieveService` | `GRAPH_BUDGET_MS` | QA 시 그래프 traverse 허용 시간(ms). 초과 시 벡터-only 폴백 | 800 | 800 | 🔲 QA 로그 필요 | — |
| `RagRetrieveService` | `RRF_K` | 벡터·그래프 순위 융합(RRF) 상수. 클수록 1등 독식 완화 | 60 | 60 | 🔲 골든셋 필요 | — |
| `RagRetrieveService` | `GRAPH_EXTRA_CITATIONS` | 벡터 topK 밖에서도 끌어올 수 있는 그래프 전용 citation 상한 | 3 | 3 | 🔲 QA 로그 필요 | — |
| `RagGraphTraversalService` / `RagGraphAdminService` | `MAX_SEEDS` | 질문에서 매칭해 탐색을 시작할 엔티티(seed) 개수 상한 | 20 | **20** | ✅ 확정 | 2026-07-23 |
| `RagGraphTraversalService` / `RagGraphAdminService` | `MAX_GRAPH_CHUNKS` | 그래프 탐색으로 회수할 청크 개수 상한 | 30 | **50** | ✅ 확정 | 2026-07-23 |
| `RagGraphTraversalService` | `SEED_WEIGHT` | seed 엔티티에 직접 걸린 청크 가중치 | 2.0 | 2.0 | 🔲 골든셋 A/B | — |
| `RagGraphTraversalService` | `HOP_WEIGHT` | seed의 1-hop 이웃 엔티티 청크 가중치 | 1.0 | 1.0 | 🔲 골든셋 A/B | — |
| `RagGraphVocabularyService` / 프롬프트 주입 | TYPE 세트 | Pass1 엔티티 타입 닫힌 어휘 (DB) | ~~Java enum~~ → **DB** | **9종** (`rag_graph_vocab_entry` kind=`TYPE`) — `ORGANIZATION`,`PERSON`,`POLICY`,`PRODUCT`,`CONCEPT`,`LOCATION`,`EVENT`,`TERM`,`WORK` | ✅ 세트 확정 · DB 이전 | 2026-07-24 |
| `RagGraphVocabularyService` / 프롬프트 주입 | RELATION 세트 | Pass2 관계 타입 닫힌 어휘 (DB) | ~~Java enum~~ → **DB** | **9종** (`rag_graph_vocab_entry` kind=`RELATION`) — `HAS`,`PART_OF`,`EXCEPTION_OF`,`CAUSES`,`REQUIRES`,`RELATED_TO`,`LOCATED_IN`,`CREATED_BY`,`BASED_ON` | ✅ 세트 확정 · DB 이전 | 2026-07-24 |
| `RagGraphVocabularyService` | 세트 밖 fallback | LLM이 어휘 밖 값을 냈을 때 흡수할 기본 TYPE/RELATION | — | TYPE→`CONCEPT`, RELATION→`RELATED_TO` | ✅ 확정 | 2026-07-24 |
| `GraphPromptTexts` | 프롬프트 본문(어휘 주입 외) | Pass1·Pass2 추출 지시문. 어휘 목록만 DB에서 주입 | — | 현행 유지 (어휘 목록은 DB에서 주입) | 🔲 골든셋 품질 반복 튜닝 | — |

**상태 범례**: ✅ 확정 / ⚠️ 조정 필요 / 🔲 미측정·미검증·A/B 대기

> **어휘 소스 이전**: 예전 `RagEntityType` / `RagRelationType` Java enum → **`rag_graph_vocab_entry` 테이블**(builtin seed + admin CRUD). Pass1/Pass2 프롬프트·정규화는 `RagGraphVocabularyService`가 DB 활성 목록을 읽는다. enum 클래스는 제거됨.

### 파라미터 외 관련 진행 (요약)

| 항목 | 상태 | 비고 |
|---|---|---|
| 3-1 어휘 조회·CRUD API | ✅ | DB `rag_graph_vocab_entry` 기반 (`GET/POST/PUT/DELETE .../vocabulary/...`) |
| 3-1 enum→DB 이전 | ✅ | Java enum 제거, builtin seed + 활성/soft-delete |
| 3-1 세트 관리 화면·open extraction 빈도표 | 🔲 잔여 | 프론트 / 부트스트랩 플로우 |
| REINDEX API | ✅ | `POST .../datasources/{documentId}/reindex` (9차) |
| 닫힌 세트 강제(2-3)·Pass1 폴백·정규화 일원화 | 🔲 잔여 | 어휘 확정 후속 |

---

## 측정이 필요한 데이터 소스

| 파라미터 그룹 | 필요한 데이터 | 확인 방법 | 비고 |
|---|---|---|---|
| `timeout-seconds`, `chunk-batch-size` | EXTRACT_ENTITY / EXTRACT_RELATION 소요 시간 | `rag_job_step_timings` | ✅ 값 확정(900 / 8). 재측정은 회귀 감시용 |
| `GRAPH_BUDGET_MS` | 그래프 탐색 소요 시간 | QA 요청 후 `RagRetrieveService` 로그 | 미확정 |
| `MAX_SEEDS`, `MAX_GRAPH_CHUNKS` | 검색 시 실제 seed 수, 회수 청크 수 | `RagGraphTraversalService` INFO 로그 | ✅ 값 확정(20 / 50). 상한 히트율 감시 |
| `RRF_K`, `SEED_WEIGHT`, `HOP_WEIGHT`, `GRAPH_EXTRA_CITATIONS` | fusion 결과 품질 | 골든셋 A/B | 미확정 |
| `entity-link-ttl-seconds` | Redis 캐시 히트율 | `entity:link:*` hit/miss | 미측정 |
| `size-threshold-bytes` | 문서 크기 분포 vs light/heavy | `rag_documents` / MinIO | 미측정 |
| `max-chars-per-chunk` | 청크 크기 분포 | Qdrant / `rag_chunks` | 미측정 |
| TYPE/RELATION 세트 | 추출 파편화·fallback 비율 | 어휘 API + Pass1/2 로그 | ✅ 세트·fallback 확정. 프롬프트 본문만 잔여 튜닝 |

---

## 2026-07-22 — 1차 실측: timeout-seconds

### 측정 쿼리

```sql
SELECT
    step,
    COUNT(*)                                                          AS 건수,
    ROUND(AVG(duration_ms))                                          AS 평균_ms,
    MIN(duration_ms)                                                  AS 최소_ms,
    MAX(duration_ms)                                                  AS 최대_ms,
    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY duration_ms)         AS 중간값_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)        AS p95_ms
FROM rag_job_step_timings
WHERE step IN ('EXTRACT_ENTITY', 'EXTRACT_RELATION')
  AND status = 'SUCCEEDED'
  AND duration_ms IS NOT NULL
GROUP BY step;
```

### 측정 결과

| step | 건수 | 평균_ms | 최소_ms | 최대_ms | 중간값_ms | p95_ms |
|---|---|---|---|---|---|---|
| EXTRACT_ENTITY | 14 | 55,688 | 15,214 | 120,011 | 48,157 | 104,988 |
| EXTRACT_RELATION | 10 | 136,767 | 39 | 523,369 | 58,328 | 514,575 |

### 분석

**EXTRACT_ENTITY (Pass1)**
- 평균 55초, 중간값 48초 — 대부분 1분 이내 처리
- 최대 120,011ms → 현재 `timeout-seconds: 120`에 정확히 걸린 케이스, timeout 직격 추정
- p95 = 105초 → 현재 120초가 상위 5%를 간신히 커버하는 수준으로 여유 없음

**EXTRACT_RELATION (Pass2)**
- 최소 39ms는 이상치 — 청크 없는 문서이거나 캐시 히트 추정, 원인 파악 필요
- 최대 523초, p95 = 514초 → 현재 120초 timeout으로 상위 50% 이상 케이스가 강제 종료될 수 있는 상태
- 중간값(58초)과 p95(514초) 격차가 극심 → 문서 크기(청크 수)에 따라 처리 시간 편차가 큼

### 조정 결론

| 파라미터 | 이전 값 | 조정 값 | 근거 |
|---|---|---|---|
| `rag.graph.timeout-seconds` | 120 | **600** | EXTRACT_RELATION p95 514초. 120초는 대부분의 관계 추출을 강제 종료시키는 값 |
| `rag.graph.chunk-batch-size` | 4 | 보류 | EXTRACT_RELATION 시간 편차 원인이 배치 크기인지 문서 크기인지 추가 확인 필요 |

### 미결 → 후속 반영

- [x] `timeout-seconds` 상향 적용·재측정 — 1차 600 제안 → **7차에서 900 확정**
- [ ] EXTRACT_RELATION 최소 39ms 케이스 문서 확인 (청크 수 0 여부)
- [x] 샘플 부족·`chunk-batch-size` 실험 — **4차에서 batch=8 확정**

---

## 2026-07-23 — 2차: 하드코딩 상수 조정 가능성 검토

### 배경

현재 보유 데이터: EXTRACT_ENTITY 14건 · EXTRACT_RELATION 10건 step timing 실측치.  
질문: 이 데이터로 코드 하드코딩 상수(`GRAPH_BUDGET_MS`, `RRF_K`, `MAX_SEEDS` 등)를 조정할 수 있는가?

---

### 데이터 수집 경로와 상수의 연관성

| 데이터 수집 시점 | 해당 상수 | 수집 가능 여부 |
|---|---|---|
| 인덱싱 (EXTRACT_ENTITY / EXTRACT_RELATION) | `timeout-seconds`, `chunk-batch-size` | ✅ step timing에 반영됨 |
| 검색/QA 실행 시 | `GRAPH_BUDGET_MS`, `MAX_SEEDS`, `MAX_GRAPH_CHUNKS`, `GRAPH_EXTRA_CITATIONS` | ❌ QA 요청 로그에만 기록됨 |
| 품질 평가 (골든셋) | `RRF_K`, `SEED_WEIGHT`, `HOP_WEIGHT` | ❌ recall/precision 측정 필요 |

**결론: 현재 step timing 데이터로는 인덱싱 파라미터만 조정 가능하고, 검색 경로 상수 7개 모두 추가 데이터 없이는 신뢰할 수 있는 조정 불가.**

---

### 상수별 판정 및 근거

#### 조정 불가 — QA 실행 로그 필요

**`GRAPH_BUDGET_MS = 800ms`**

`RagRetrieveService.augmentWithGraph()`에서 `CompletableFuture.get(GRAPH_BUDGET_MS, ...)` 타임아웃으로 사용.  
초과 시 벡터-only 폴백. 그래프 탐색(엔티티 DB 조회 × 3쿼리)이 실제로 얼마나 걸리는지는 QA 요청 시에만 측정된다.

- 확인이 필요한 로그 패턴: `[RAG][*][GRAPH] fused vector=... graph=...`
- 측정 방법: 해당 로그에 traverse 소요 시간을 추가하거나, `CompletableFuture.get()` 호출 전후에 `System.nanoTime()` 찍기

```java
// RagRetrieveService.augmentWithGraph() 에 추가할 계측 코드 (현재 미적용)
long t0 = System.nanoTime();
List<UUID> graphChunkIds = CompletableFuture
        .supplyAsync(() -> graphTraversalService.traverse(query, authorizedDocIds))
        .get(GRAPH_BUDGET_MS, TimeUnit.MILLISECONDS);
long traverseMs = (System.nanoTime() - t0) / 1_000_000;
log.info("[RAG][{}][GRAPH] traverseMs={}", transactionId, traverseMs);
```

조정 전까지 800ms 유지. 문서 수가 증가하면 DB 쿼리 규모도 커지므로 이때 재측정.

---

**`MAX_SEEDS = 20`, `MAX_GRAPH_CHUNKS = 30`**

`RagGraphTraversalService.traverse()` 내부에서 seed 수집과 청크 랭킹 상한으로 쓰인다.  
현재 로그 수준이 DEBUG여서 실제 seed 수, neighbor 수, 청크 수가 기록되지 않음.

```
log.debug("[RAG_GRAPH_TRAVERSE] seeds={} neighbors={} chunks={}", ...)
```

- 측정 방법: 로그 레벨을 일시적으로 INFO로 올리거나, 위 로그를 INFO로 격상한 뒤 QA 요청 실행
- 조정 기준: 실제 seed 수가 MAX_SEEDS 상한을 자주 치면 올리고, 평균 seed가 5 미만이면 줄여도 됨

조정 전까지 20 / 30 유지.

---

**`GRAPH_EXTRA_CITATIONS = 3`**

`fuseByRrf()`에서 벡터 topK 밖에 추가로 끌어올릴 수 있는 그래프 전용 청크 상한.  
이 값이 품질에 기여하는지는 "그래프-only로 찾아서 답에 실제 기여한 건수"를 측정해야 알 수 있음.

```
[RAG][{}][GRAPH] fused vector=... graph=... graphOnlyPromoted={} final={}
```

`graphOnlyPromoted` 가 0에 가까우면 GRAPH_EXTRA_CITATIONS를 키워도 무의미하고, 자주 상한에 걸리면 늘려야 한다.  
QA 로그 없이는 판단 불가. 3 유지.

---

#### 조정 불가 — 골든셋(품질 평가) 필요

**`RRF_K = 60`**

RRF 공식 `1/(k + rank)`의 k값. 값이 클수록 1등이 전체를 독식하는 효과가 완화된다.  
k=60은 학술 논문에서 널리 쓰이는 경험값이며, 변경 효과를 보려면 벡터-only 대비 벡터+그래프 fusion의 recall/precision을 비교해야 한다.  
골든셋(2-2) 없이는 "품질이 올랐는지 떨어졌는지" 판단 기준이 없음. 60 유지.

**`SEED_WEIGHT = 2.0`, `HOP_WEIGHT = 1.0`**

seed 엔티티와 1-hop 이웃 엔티티의 청크 가중치 비율(현재 2:1).  
직관적으로는 직접 매칭이 이웃보다 중요하므로 2:1 비율은 합리적이나, 비율을 바꾸면 응답 품질이 어떻게 달라지는지는 골든셋 recall 측정으로만 확인 가능.  
골든셋 전까지 2.0 / 1.0 유지.

---

### 조정 보류 중 — chunk-batch-size 실험 설계

1차 실측(2026-07-22)에서 EXTRACT_RELATION 시간 분포가 극단적으로 넓었다.

```
최소 39ms → 최대 523s, 중간값 58s, p95 514s
```

chunk-batch-size=4인 상태에서 청크 수가 많은 문서는 LLM 배치 호출 횟수가 많아져 시간이 급증한다.  
`총 시간 ≈ ceil(청크_수 / batch_size) × 배치당_LLM_지연`

**실험 설계 (다음 단계)**

```sql
-- 문서별 청크 수 분포 확인
SELECT
    d.id                             AS document_id,
    COUNT(c.id)                      AS 청크_수,
    t.step,
    t.duration_ms
FROM rag_documents d
JOIN rag_chunks c ON c.document_id = d.id
LEFT JOIN rag_job_step_timings t
    ON t.document_id = d.id AND t.step = 'EXTRACT_RELATION' AND t.status = 'SUCCEEDED'
GROUP BY d.id, t.step, t.duration_ms
ORDER BY 청크_수 DESC;
```

위 쿼리로 "청크_수 vs EXTRACT_RELATION 소요_시간" 관계를 확인한 뒤:
- 청크 수와 소요 시간이 비례하면 → batch_size 조정 실험 실행 (4 → 2 vs 4 → 8)
- 비례하지 않으면 → LLM API 지연 편차 문제이므로 batch_size 조정 무의미

chunk-batch-size 조정은 위 쿼리 실행 후 판단. **현재 4 유지.**

---

### 요약

| 상수 | 현재 값 | 조정 가능 여부 | 필요 데이터 |
|---|---|---|---|
| `timeout-seconds` | 600 | ✅ 완료 | step timing (완료) |
| `GRAPH_BUDGET_MS` | 800ms | ❌ 불가 | QA traverse 타이밍 로그 |
| `RRF_K` | 60 | ❌ 불가 | 골든셋 recall/precision |
| `GRAPH_EXTRA_CITATIONS` | 3 | ❌ 불가 | QA 로그 graphOnlyPromoted 분포 |
| `MAX_SEEDS` | 20 | ❌ 불가 | QA 로그 실제 seed 수 |
| `MAX_GRAPH_CHUNKS` | 30 | ❌ 불가 | QA 로그 실제 청크 수 |
| `SEED_WEIGHT` / `HOP_WEIGHT` | 2.0 / 1.0 | ❌ 불가 | 골든셋 품질 비교 |
| `chunk-batch-size` | 4 → **8** | ✅ 4차 확정 | batch=8 재인덱싱 실측 (시간·bridges/entity) |

### 다음 액션

- [ ] `RagRetrieveService.augmentWithGraph()`에 traverse 소요 시간 INFO 로그 추가
- [ ] `RagGraphTraversalService.traverse()` 마지막 log를 DEBUG → INFO로 격상
- [ ] 청크 수 vs EXTRACT_RELATION 시간 상관 쿼리 실행 → chunk-batch-size 조정 여부 판단
- [ ] 골든셋 20~50문항 준비 후 RRF_K / SEED_WEIGHT / HOP_WEIGHT 실험

---

## 2026-07-23 — 3차: DB 실측 기반 상수 조정

### 측정 쿼리 및 결과

**쿼리 1 — 청크 수 vs EXTRACT_RELATION 소요 시간**

주요 행 발췌 (EXTRACT_RELATION 기록 있는 문서만):

| 문서 | 청크_수 | relation_ms | attempt | 엔티티_수 |
|---|---|---|---|---|
| `d80bdaeb` | 1,226 | 1,033,349 | 1 | (미추출) |
| `0190abda` | 2,548 | 523,369 | 1 | 200 |
| `0d73e25c` | 2,548 | 503,826 | 1 | — |
| `b6869347` | 113 | 706,288 | 1 | 282 |
| `142d9218` | 112 | 596,135 | 1 | 299 |
| `05c8b516` | 33 | 267,854 | 1 | 189 |
| `511e005d` | 30 | 238,670 | 1 | 135 |
| `be4c69a6` | 30 | 234,421 | 1 | 169 |
| `4c09ada9` | 25 | 207,041 | 1 | 153 |
| `5f0b3956` | 17 | 288,049 | 3 | 86 |
| `a72857e1` | 20 | 146,697 | 1 | 127 |
| `6c706f14` | 18 | 136,692 | 1 | 67 |
| `abaa8022` | 802 | 36,502 | 1 | 10 |
| `f133bb74` | 519 | 872 | 1 | 0 (미추출) |
| `7db13387` | 132 | 37 | 2 | — |
| `4e1058e0` | 16 | 47 | 3 | — |

**쿼리 2 — 그래프 데이터 현황**

| 타입 | 건수 |
|---|---|
| entity | 2,377 |
| relation | 1,701 |
| bridge | 3,710 |

**쿼리 3 — 문서별 엔티티 수 (상위)**

| 문서 | 엔티티 수 |
|---|---|
| `142d9218` | 299 |
| `b6869347` | 282 |
| `0190abda` | 200 |
| `05c8b516` | 189 |
| `301fa8b7` | 188 |
| `be4c69a6` | 169 |
| `abaa8022` | 10 |

---

### 분석

#### ① EXTRACT_RELATION 시간의 실제 드라이버 = 청크 수가 아니라 엔티티 수

가장 명확한 반증 사례:
- `f133bb74`: 청크 519개, 엔티티 0개 → **872ms** (즉시 완료)
- `abaa8022`: 청크 802개, 엔티티 10개 → **36s**
- `0190abda`: 청크 2548개, 엔티티 200개 → **523s**

청크 수 대신 엔티티 수 기반 모델:

```
예상 시간 ≈ ceil(entity_count / chunk_batch_size) × ~10s/배치
```

검증:

| 문서 | 엔티티 | ceil(n/4) | 예상(×10s) | 실측 |
|---|---|---|---|---|
| `abaa8022` | 10 | 3 | 30s | 36s ✓ |
| `6c706f14` | 67 | 17 | 170s | 137s ✓ |
| `a72857e1` | 127 | 32 | 320s | 147s △ |
| `0190abda` | 200 | 50 | 500s | 523s ✓ |
| `b6869347` | 282 | 71 | 710s | 706s ✓ |
| `142d9218` | 299 | 75 | 750s | 596s △ |

대부분 잘 맞음. 일부 편차는 엔티티 당 청크 수(각 청크의 텍스트 길이) 차이에서 기인하는 것으로 추정.

**결론**: `chunk-batch-size`는 "엔티티를 품은 청크"를 묶는 단위이며, 엔티티 없는 청크는 EXTRACT_RELATION에서 건너뜀.

#### ② timeout-seconds 600이 일부 문서를 커버 못 함

- `b6869347` (282 엔티티) → 706s : **현재 timeout 600s 초과**
- `142d9218` (299 엔티티) → 596s : 4s 여유로 간신히 통과
- 300 엔티티 문서 예상: ceil(300/4) × 10s = 750s → timeout 초과 확실

조정: **600s → 900s** (엔티티 360개 문서까지 커버, 현재 최대 299개 기준 여유 30%)

#### ③ attempt 2+ 초고속 완료 케이스

`7db13387`(132청크, 37ms, attempt 2), `6faf8cf4`(32청크, 58ms, attempt 2), `4e1058e0`(16청크, 47ms, attempt 3)

이전 attempt에서 엔티티/관계가 이미 저장된 상태에서 재시도하면 처리 대상이 없어 즉시 완료. 정상 동작.

#### ④ bridge:entity 비율과 MAX_GRAPH_CHUNKS

```
bridge 3,710 / entity 2,377 = 1.56 bridges per entity
```

QA 검색 시 MAX_SEEDS=20 기준:
- seed 20개 → 20 × 1.56 = **31개 seed-chunk** (이미 MAX_GRAPH_CHUNKS=30 초과)
- 1-hop 이웃 ~40개 추가 → 40 × 1.56 = 62개 neighbor-chunk
- 중복 제거 후 전체 후보 약 50~70개 → 현재 MAX_GRAPH_CHUNKS=30이 절반 이상 잘라냄

조정: **30 → 50** (seed-chunk를 온전히 담고, 상위 1-hop까지 일부 포함)

#### ⑤ MAX_SEEDS=20 적정성 확인

현재 문서당 최대 엔티티 수: 299개. QA 쿼리 1건으로 20개 이상의 엔티티 이름이 동시에 매칭될 가능성은 매우 낮음. **20 유지.**

---

### 조정 결론

| 파라미터 | 이전 값 | 조정 값 | 근거 |
|---|---|---|---|
| `timeout-seconds` | 600 | **900** | 282 엔티티 문서 실측 706s. 300 엔티티 @ batch=4 예상 750s |
| `MAX_GRAPH_CHUNKS` | 30 | **50** | bridge:entity 비율 1.56. seed 20개만으로도 31개 청크 → 30은 너무 빡빡 |
| `MAX_SEEDS` | 20 | **유지** | 문서당 최대 엔티티 299개. 20 seed는 적절한 상한 |

### 미결 — chunk-batch-size 실험 → ✅ 4차에서 확정

당시 예상: batch=8이면 배치 수 절반으로 ~28% 단축, 단 프롬프트 증가로 품질 저하 위험.  
→ **4차 실측**: 시간 ~20% 단축, bridges/entity 2.41(범위 내), 품질 저하 없음 → **8 확정**.

---

## 2026-07-23 — 4차: chunk-batch-size=8 실험 확정

### 배경

3차에서 `chunk-batch-size`를 4→8로 올리면 EXTRACT_RELATION 배치 수가 절반이 되어 시간 단축이 기대되나, 배치당 프롬프트 증가로 관계 추출 품질이 떨어질 위험이 있었다.  
`RAG_GRAPH_CHUNK_BATCH_SIZE=8`(`GRAPH_RAG_CHUNK_SIZE` / `rag.graph.chunk-batch-size`)로 재인덱싱 후 시간·품질을 비교했다.

---

### 브리지 실제 현황 (품질 지표)

| 문서 | 엔티티 | 관계 | 브리지 | bridges/entity | batch |
|---|---|---|---|---|---|
| (기존) | — | — | — | **1.84** | 4 |
| (기존, 이상치) | — | — | 0 | — | 4 |
| (기존) | — | — | — | **2.84** | 4 |
| `68cbe002` | 227 | 175 | 548 | **2.41** | **8** |

batch=4 정상 문서의 bridges/entity 범위는 **1.84 ~ 2.84**.  
batch=8 문서(`68cbe002`)는 **2.41**로 해당 범위 안에 들어 품질 저하 없음.

---

### batch=8 실험 결론

| 항목 | batch=4 추정 | batch=8 실측 | 판정 |
|---|---|---|---|
| EXTRACT_RELATION 시간 | ~593s (엔티티 227 기준) | **474.7s** | ✅ ~**20% 단축** |
| bridges/entity | 1.84 ~ 2.84 | **2.41** | ✅ 범위 내 |
| 품질 저하 | — | **없음** | ✅ |

---

### 조정 결론

| 파라미터 | 이전 값 | 조정 값 | 근거 |
|---|---|---|---|
| `rag.graph.chunk-batch-size` (`GRAPH_RAG_CHUNK_SIZE` / `RAG_GRAPH_CHUNK_BATCH_SIZE`) | 4 | **8** | 시간 20% 단축(474.7s vs ~593s), bridges/entity 2.41로 품질 영향 없음 |

**확정**: batch=8이 시간 20% 단축에 품질 영향 없음 → **batch=8 그대로 확정.**

### 미결

- [ ] 300+ 엔티티 문서에서 timeout=900 + batch=8 조합으로 완료 확인
- [ ] QA 실행 후 MAX_GRAPH_CHUNKS=50 효과 확인 (traverseMs, graphOnlyPromoted 변화)

---

## 2026-07-23 — 5차: 골든셋 1차 실행 (수능 20문항)

### 결과 요약 (UI 실행)

| 구분 | 문항 | 비고 |
|---|---|---|
| 실답 생성 | **G-05, G-06, G-18, G-20** | 그 외 16문항은 AIG `QA_NO_CONTEXT_ANSWER` (정보 부족 고정 문구) |
| 벡터 실패 → 그래프만 답 | **G-05, G-20** | 그래프 fusion 이득 후보 |
| cite V/G | 거의 전부 N/N | 채점용 UUID가 **fileId** 였음 (아래 교정) |

### 원인

1. **카테고리 오염**: `2089206e-…` 에 INDEXED 문서 **48건** (수능 8~9 + 법령 등). qa-compare가 category 전체 검색 → 무관한 청크가 topK에 섞이면 LLM이 정보 부족 문구로 폴백.
2. **documentId 오기입**: 골든셋에 `fileId`를 `documentId`로 넣음 → citation 자동 채점 전부 N. `docs/goldenset.json` 교정 완료.
3. **그래프 의존성**: traverse 스코프가 **벡터 hit 문서 ID**에 묶임 (`RagRetrieveService`). 벡터가 엉뚱한 문서만 치면 그래프도 그 안에서만 탐색.

### 조치 / 다음

- [x] `goldenset.json` / draft 코퍼스 표를 실제 `documentId`로 갱신
- [ ] 골든셋 전용 카테고리(수능 8문서만) 분리 후 동일 20문항 재실행
- [ ] 또는 평가 시 must_cite 문서 ID만 스코프로 넣는 비교 모드 검토
- [ ] 2025-수학 동일 파일명 중복(`bbf98aa5` / `a7556554`) 정리

---

## 2026-07-23 — 6차: 골든셋 2차 실행 (category 이관 + Qdrant payload 교정 후)

### 선행 조치

- PG `category_id` → `a161b923-adbc-4d86-8329-f9a901e1c44d`
- Qdrant 수능 9문서 payload `category_id`만 동일 값으로 set (재임베딩 없음)
- `goldenset.json` documentId = 실제 document UUID (fileId 아님)

### Summary (UI)

| 지표 | 값 |
|---|---|
| ran | 20 |
| citation OK (graph) | **8** |
| graph win Y | **11** |
| no-regression Y | **5** (control 전원) |
| HTTP/ERR | **1** (G-14) |

### 문항별 관찰 (요약)

| 그룹 | 상대적으로 양호 | 약함 / 실패 |
|---|---|---|
| cross-year | G-02 cite Y/Y (ans는 vec만 키워드) | G-01·03~09·15 다수 ans N(0/x), cite도 혼재 |
| within-doc | **G-12·G-13 cite+ans Y/Y** | G-10/11 혼재, **G-14 ERR** |
| control | **G-17 Y/Y**, G-18 graph ans Y, noreg 전부 Y | G-16·19·20 ans 키워드 N |

### 1차 대비

| | 1차 (오염+fileId) | 2차 (Qdrant category 교정) |
|---|---|---|
| cite OK (graph) | ~0 | **8** |
| 실답·키워드 통과 | 극소수 | within-doc·control 일부 개선 |
| 환경 타당성 | 불가 | **검색 스코프는 수능 쪽으로 회복** |

### 결론 (하드코딩 파라미터 관점)

1. **이번에 확정하지 않음**: `RRF_K` / `SEED_WEIGHT` / `HOP_WEIGHT` / `MAX_SEEDS` 등은 아직 숫자로 확정할 단계 아님.  
   - ans 키워드 실패가 다수 → 정보부족 폴백·키워드 엄격도·프롬프트 이슈가 fusion 상수와 섞여 있음.  
   - graph win 11은 cite 수 비교 위주라 **품질 승리로 과대해석 금지**.
2. **이번에 확인된 것**:  
   - Qdrant `category_id` 불일치가 해소되면 citation·일부 within-doc/control이 살아남.  
   - **G-12·G-13** 은 Graph+Vector 모두 안정 → within-doc 연결형 검증 가능 신호.  
   - control **no-regression 5/5** → 그래프가 단순 조회를 크게 해치지 않음.  
   - **G-14 ERR** · 고지연(G-10/G-13 vec 30~40s) 은 별도 장애/타임아웃 추적 필요.
3. **다음 튜닝 입력으로 쓸 것**: 동일 20문항을 **정보부족이 아닌 실답 비율**이 올라온 뒤,  
   - Graph Win이면서 cite OK인 문항(예: G-12/13) vs FAIL 문항 diff로 `MAX_GRAPH_CHUNKS` / `GRAPH_EXTRA_CITATIONS`부터 조정.

### 미결

- [ ] G-14 HTTP/ERR 원인 (timeout·gateway·모델)
- [ ] ans N 다수 문항 Detail에서 `QA_NO_CONTEXT_ANSWER` 여부 확인 (→ 8차 UI에서 `NOCTX` 분리)
- [x] 그래프 비어 있는 수능 문서 재추출 경로 — **9차 REINDEX** (실행·coverage 재확인은 운영 작업)
- [ ] 위 안정화 후 RRF_K / SEED_WEIGHT / HOP_WEIGHT A/B

---

## 2026-07-23 — 7차: 확정 파라미터 코드 반영

골든셋 2차까지로 **확정 가능한 값만** 코드·fallback에 고정. fusion 상수(`RRF_K` 등)는 미확정 유지.  
(어휘 TYPE/RELATION은 당시 미정 → **2026-07-24에 세트·fallback 확정**, 상단 요약 표 참고)

| 파라미터 | 확정값 | 반영 위치 |
|---|---|---|
| `rag.graph.timeout-seconds` | **900** | YAML(local/dev-consumer) 기존 + `RagProperties` fallback 120→**900** |
| `rag.graph.chunk-batch-size` | **8** | YAML 기존 + `RagProperties` fallback 5→**8** |
| `MAX_SEEDS` | **20** | `RagGraphTraversalService` / `RagGraphAdminService` |
| `MAX_GRAPH_CHUNKS` | **50** | Traversal 기존 50 + Admin debug **30→50** 정렬 |

미확정(변경 없음, fusion/검색): `GRAPH_BUDGET_MS`, `RRF_K`, `GRAPH_EXTRA_CITATIONS`, `SEED_WEIGHT`, `HOP_WEIGHT`.

---

## 2026-07-23 — 8차: 2일차 착수 (2-1 잔여 계측 + 3-1 기반)

### 목표

1일차에서 확정 못한 fusion 상수 전에, **진단·어휘 조회**부터 고정.

### 구현

| 항목 | 내용 |
|---|---|
| 골든셋 UI | ans를 `Y` / `N(k/n)` / **`NOCTX`**(정보부족 고정문구)로 분리, summary에 no-context (graph) 카운트 |
| `GET /api/rag/admin/graph/coverage?categoryId=` | 문서별 entity/relation/bridge 건수·`graphReady` |
| `GET /api/rag/admin/graph/vocabulary` | 닫힌 TYPE/RELATION 조회 (**당시 Java enum → 이후 `rag_graph_vocab_entry` DB·CRUD**, 2026-07-24) |
| traverse 로그 | 기존 INFO `seeds/neighbors/chunks` 유지 (추가 계측 불필요 확인) |

### 다음 (2일차 이어서)

- [x] coverage로 entities=0 수능 문서 확인 → **9차 REINDEX API·스크립트**로 복구 경로 확보
- [ ] NOCTX 비율 재측정 후 미니셋으로 `GRAPH_EXTRA_CITATIONS` / `RRF_K` A/B
- [x] 3-1: vocabulary 조회·CRUD API (추가/활성·비활성/soft-delete) — 2026-07-24
- [ ] 3-1: 세트 관리 화면(동결/활성 표시) · open extraction 빈도표 부트스트랩 플로우
- [x] 3-1: builtin TYPE/RELATION 세트·fallback 확정 — 2026-07-24 (`WORK` + `LOCATED_IN`/`CREATED_BY`/`BASED_ON`)

---

## 2026-07-23 — 9차: 2일차 계속 (그래프 공백 복구 경로)

### 진단 (기존 graph/documents API)

수능 9문서 중 **graphReady=false (e=0)** 4건:

| documentId | 문서 |
|---|---|
| `a7556554-…` | 2025-수학 (중복 업로드) |
| `d5a16114-…` | 2026-국어 |
| `f63f665c-…` | 2026-영어 |
| `5b2cabf7-…` | 2025-한국사 |

ready: 2025-국어·영어·수학(bbf98aa5), 2026-수학·한국사.

> `GET .../graph/coverage` 는 API 재기동 전엔 404. 재빌드 후 사용.

### 구현

| 항목 | 내용 |
|---|---|
| `POST /api/rag/admin/datasources/{documentId}/reindex` | REINDEX job 큐잉 (Pass1부터). consumer `graph.enabled` 필요 |
| `scripts/reindex-graph-empty.ps1` | 위 4문서 일괄 요청 |
| `RagGraphVocabularyServiceTest` | 세트 밖 relation/type → DB 어휘 fallback 단위 테스트 (2-3) |

### 실행 순서

1. API + consumer 재빌드/재기동  
2. `.\scripts\reindex-graph-empty.ps1`  
3. job SUCCEEDED·coverage `graphReady=true` 확인  
4. 골든셋 재실행 (NOCTX 비율)  
5. 미니셋으로 fusion 상수 A/B

---

## 2026-07-24 — 3-1 어휘 확정 (업로드 엔티티 검수)

### 전제

- open extraction 빈도표 플로우는 후순위. **현재 builtin + 업로드로 쌓인 엔티티 인스턴스**로 전역 세트 확정.
- 설계: TYPE 10~20, RELATION 6~10(타이트). 세트 밖 → `CONCEPT` / `RELATED_TO`.

### 검수 전 builtin

| 구분 | 항목 |
|---|---|
| TYPE (8) | `ORGANIZATION`, `PERSON`, `POLICY`, `PRODUCT`, `CONCEPT`, `LOCATION`, `EVENT`, `TERM` |
| RELATION (6) | `HAS`, `PART_OF`, `EXCEPTION_OF`, `CAUSES`, `REQUIRES`, `RELATED_TO` |

### 업로드 엔티티 패턴 → 매핑

목록은 **타입명이 아닌 인스턴스 이름**(수능·한국사·보안가이드·영문지문 등).

| 군 | 예시 | 기존 TYPE |
|---|---|---|
| 인물 | 김구, 칸트, 이시영 | `PERSON` |
| 장소 | 강원도, 압록강, UC Davis | `LOCATION` |
| 기관 | 산림청, 헌법재판소, KISA | `ORGANIZATION` |
| 사건·시험 | 청산리 전투, 수능 | `EVENT` |
| 법령·제도 | 민법, 근로기준법 | `POLICY` |
| 상품·플랫폼 | OTT, 웹툰(상품성) | `PRODUCT` |
| 추상 개념 | 곡률, 문화 상대주의 | `CONCEPT` |
| 기술 용어 | pam_*, U-01 | `TERM` |
| **문헌·작품·시험지·원작** | 동의보감, 수궁가, 웹툰/드라마 원작 | **애매** (`PRODUCT`/`CONCEPT`로 흩어짐) |

관계 쪽 빈칸: **장소 귀속**, **저작**, **원작→파생(영상화)** — `RELATED_TO`만으로는 파편화·탐색 품질 저하.

### 결정: 추가

| 구분 | name | 용도 |
|---|---|---|
| TYPE | `WORK` | 문헌·작품·시험지·미디어 콘텐츠·원작 (`PRODUCT`와 구분) |
| RELATION | `LOCATED_IN` | `(EVENT\|ORG\|PERSON\|WORK) → LOCATION` |
| RELATION | `CREATED_BY` | `WORK → PERSON` (필요 시 ORG) |
| RELATION | `BASED_ON` | `WORK → WORK` (예: 영상화 → 웹툰) |

### 추가하지 않음

| 후보 | 이유 |
|---|---|
| `LAW` | `POLICY`로 흡수 |
| `SUBJECT` | `CONCEPT`/`TERM` |
| `MEMBER_OF` / `OCCURRED_AT` / `REGULATES` | 기존 RELATION으로 충분·중복 |

### 확정 builtin (검수 후)

| 구분 | 항목 |
|---|---|
| TYPE (9) | 기존 8 + **`WORK`** |
| RELATION (9) | 기존 6 + **`LOCATED_IN`**, **`CREATED_BY`**, **`BASED_ON`** |
| fallback | TYPE→`CONCEPT`, RELATION→`RELATED_TO` |

### 코드·DB

| 항목 | 내용 |
|---|---|
| `RagGraphVocabularyService` | `BUILTIN_*` 갱신, 기동 시 `ensureBuiltins()`(없는 것만 INSERT) |
| SQL | `docs/sql/rag_graph_vocab_entry_seed_work_located_created_based.sql` |
| API | 조회·CRUD 완료. admin 어휘 관리 화면은 프론트 잔여 — API로 초기/활성/soft-delete 확인 |

### 다음

- [ ] rag-storage 재기동(또는 seed SQL) 후 어휘 API에 `WORK`·3 relation 노출 확인
- [ ] 3-1 잔여: 세트 관리 화면(동결/활성) · open extraction 빈도표 플로우
- [ ] 2-3: 닫힌 세트 강제·Pass1 폴백·정규화 일원화 (어휘 확정 후속)
- [ ] NOCTX↓ 확인 후 `GRAPH_EXTRA_CITATIONS` / `RRF_K` / `SEED_WEIGHT` / `HOP_WEIGHT` / `GRAPH_BUDGET_MS` A/B

<!-- 다음 실측은 아래에 동일한 형식으로 섹션 추가 -->
