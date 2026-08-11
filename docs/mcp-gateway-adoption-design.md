# MCP 게이트웨이 도입 설계

> 에이전트가 사내 API를 "툴"처럼 호출할 수 있도록, 기존 게이트웨이 체인은 그대로 두고 그 앞에 MCP(Model Context Protocol) 변환 어댑터를 얹는 설계.

---

## 한 줄 요약

지금 구조엔 에이전트가 REST API를 툴로 호출할 표준 방법(MCP)이 없다. 인증·라우팅(gateway-backend)과 LLM 오케스트레이션(aigateway)은 이미 잘 구축돼 있으므로 **걷어내고 대체하는 게 아니라**, 그 앞에 REST→MCP 변환 어댑터 하나만 새로 추가한다.

---

## 1. 무엇이 문제인가

에이전트(LLM 기반 자동화)가 사내 API를 사용하려면 지금은 서비스마다 커스텀 통합 코드를 매번 새로 짜야 한다. REST 엔드포인트를 표준 프로토콜(MCP)로 노출해두면, 에이전트는 "이 서버에 어떤 툴이 있는지 조회 → 파라미터 채워 호출"만으로 바로 쓸 수 있다. 통합 코드를 서비스 개수만큼 반복해서 만들 필요가 없어진다.

---

## 2. MCP·REST-to-MCP 변환이란

**MCP**는 에이전트가 외부 기능을 "툴"로 호출하는 표준 프로토콜이다. **REST-to-MCP 변환기**는 기존 OpenAPI 스펙을 읽어서 각 REST 엔드포인트를 자동으로 MCP 툴 정의(툴 이름, 입력 파라미터, 설명)로 바꿔준다.

> 핵심은 **API 로직을 새로 짜는 게 아니라는 점**이다. 앞단에 "MCP 말을 REST 호출로 바꿔주는 번역기"를 하나 씌우는 것뿐이라, 기존 서비스 코드는 손대지 않는다.

---

## 3. 확인된 현재 구조

에이전트/AI 관련 트래픽은 이미 3단 체인으로 처리되고 있다.

```
클라이언트
     │
     ▼
gateway-backend  (Spring Cloud Gateway)
  - AuthFilter        : X-API-Key/Bearer/query → Redis UAK 검증 (실동작)
  - PromptGuardFilter  : 프롬프트 인젝션·PII 마스킹 (dev)
  - RateLimitFilter    : 껍데기만 있고 미구현  ← 유일한 공백
     │
     ├─ /api/ai/**  → aigateway
     │     OpenAI/Gemini/Claude 벤더 추상화, QA 프롬프트 조립,
     │     LangchainClient로 세션 컨텍스트 연동
     │
     └─ /api/rag/** → rag-storage-service
           포탈(문서 조회) · 어드민(카테고리/데이터소스) · QA 오케스트레이션 ·
           Graph RAG 파이프라인
```

- **인증·라우팅·프롬프트 가드**는 gateway-backend에 이미 구현·운영 중.
- **LLM 벤더 추상화·QA 조립**은 aigateway에 이미 구현·운영 중.
- 두 저장소 어디에도 **MCP/툴 자동 노출 기능은 없음** — 이 문서가 다루는 유일한 공백.

---

## 4. 도입하면 뭐가 달라지나

| 구분 | 항목 | 판단 |
|---|---|---|
| **신규 추가** | MCP 툴 노출 | 어디에도 없던 기능. 도입 목적의 핵심 |
| **신규 추가 (선택)** | 레이트리밋 | gateway-backend에 껍데기만 있고 미구현 — MCP 경유 트래픽만 우선 커버할지, REST 전체를 위해 별도로 채울지는 독립 결정 사항 |
| **대체 불필요** | 인증(UAK 검증) | gateway-backend AuthFilter가 이미 실동작 — 새 게이트웨이가 대체하면 오히려 손해 |
| **대체 불필요** | LLM 벤더 추상화·QA 조립 | aigateway가 이미 담당 — 손댈 이유 없음 |
| **대체 불필요** | Graph RAG 파이프라인·비즈니스 로직 | rag-storage-service 내부 로직, 게이트웨이와 무관 |

**결론**: "대체"가 아니라 **"추가"**다. 기존 체인 맨 앞에 에이전트 전용 프로토콜 어댑터 하나를 얹는 것으로 설계 방향을 잡는다.

---

## 5. 아키텍처 구조 (배치 설계)

```
[MCP 클라이언트 / 에이전트]
       │  MCP 프로토콜 (JSON-RPC over stdio/SSE)
       ▼
╔══════════════════════════════════════════════════╗
║ MCP 게이트웨이 (신규)
║  - rag-storage-service·aigateway의 OpenAPI 스펙을
║    등록해 REST 엔드포인트를 MCP 툴로 자동 노출
║  - MCP 툴 호출 → 대상 REST 요청으로 변환
║  - 에이전트가 이미 보유한 credential(X-API-Key 등)을
║    그대로 실어서 아래로 전달 (자체 인증 로직 없음)
╚══════════════════════════╤═════════════════════════╝
                            ▼
                  gateway-backend        ← 변경 없음
                  (AuthFilter·PromptGuardFilter 그대로 검증)
                            │
                  ┌─────────┴─────────┐
                  ▼                   ▼
              aigateway        rag-storage-service
              (변경 없음)         (변경 없음)
```

**설계 원칙**: MCP 게이트웨이는 gateway-backend를 우회하지 않고 **"또 하나의 정상 클라이언트"**로서 그 앞단에 붙는다. 이렇게 하면:

- gateway-backend의 AuthFilter가 요구하는 검증을 그대로 통과시키면 되므로 gateway-backend 코드 변경이 필요 없다.
- aigateway·rag-storage-service는 자신을 호출하는 게 사람인지 에이전트인지 구분할 필요가 없다 (게이트웨이 체인이 이미 인증을 마친 요청만 내려보내므로).
- MCP 어댑터 도입이 실패하거나 롤백돼도 기존 REST 클라이언트 트래픽에는 영향이 없다.

---

## 6. 만들 때 신경 써야 할 점

| 항목 | 걱정 | 대응 |
|---|---|---|
| **OpenAPI 스펙 부재** | rag-storage-service·aigateway 모두 OpenAPI 스펙이 없어 REST-to-MCP 자동 변환의 입력 자체가 없음 | 대상 엔드포인트부터 우선 스펙 작성(springdoc-openapi 또는 수기), 전체가 아니라 노출 대상만 |
| **노출 범위** | 어드민 CRUD(카테고리 삭제, 데이터소스 변경 등)를 에이전트가 그대로 호출하면 오조작 리스크 | 1차는 조회·QA 등 읽기 중심 엔드포인트만 MCP 툴로 등록, 쓰기·삭제는 후순위 검토 |
| **바이너리 응답** | 문서 다운로드처럼 파일을 반환하는 엔드포인트는 MCP의 텍스트/구조화 콘텐츠 모델과 맞지 않음 | base64 인코딩 콘텐츠 또는 MCP `resource`(다운로드 링크) 타입으로 별도 매핑 규칙 필요 |
| **서비스 계정 인증** | gateway-backend AuthFilter가 MCP 게이트웨이발 요청을 정상 클라이언트로 인식해야 함 | MCP 게이트웨이용 UAK(서비스 계정) 발급, 요청마다 정상 헤더 실어 전달 |
| **레이트리밋 중복** | MCP 게이트웨이 자체 레이트리밋과 gateway-backend의 (예정된) RateLimitFilter가 이중 적용될 수 있음 | 레이트리밋 정책의 단일 소유 지점을 먼저 정하고 도입 |
| **네이밍 혼동** | "aigateway"(사내 LLM 오케스트레이션 서비스)와 "MCP 게이트웨이"(신규 어댑터)가 이름이 겹치기 쉬움 | 내부 문서·커뮤니케이션에서 명확히 구분해 표기 |

---

## 7. 진행 순서

| 단계 | 내용 | 목적 |
|---|---|---|
| 0 | MCP로 노출할 엔드포인트 확정 (읽기·QA 중심) | 범위를 좁혀 리스크 최소화 |
| 1 | 대상 엔드포인트 OpenAPI 스펙 작성 | REST-to-MCP 변환의 입력 확보 |
| 2 | 오픈소스 MCP 게이트웨이(mcp-context-forge 등)로 PoC 구축, 아웃바운드는 gateway-backend로 고정 | 기존 인증 체계를 재사용하며 검증 |
| 3 | 서비스 계정 UAK 발급·gateway-backend 통과 확인 | 기존 체인과의 접점 검증 |
| 4 | 바이너리 응답(다운로드 등) 매핑 규칙 확정 | 실사용 가능한 툴 범위 확장 |
| 5 | 레이트리밋 정책 소유권 결정 (MCP 게이트웨이 vs gateway-backend RateLimitFilter) | 공백 메우기, 이중 적용 방지 |
| 6 | PoC 결과 기반 상용(API Connect 등) 전환 여부 결정 | 투자 확대 판단 |

---

## 부록: 개발 세부 (미확정·구체화 후보)

- **rag-storage-service** 대상 컨트롤러(1차 후보): `RagQaController`(`/api/rag/{aiServiceName}/qa`), `PortalDocumentController`, `PortalCategoryController`, `PipelineJobController`(조회성 엔드포인트만).
- **aigateway** 대상 컨트롤러(1차 후보): `AIController`(`/api/ai/{aiServiceName}/chat/stream`), `EmbeddingController`(`/api/ai/{aiServiceName}/embedding/request`).
- **gateway-backend** 필터 체인 순서(참고, 변경 없음): `GlobalMetricFilter`(-10000) → `AuthFilter`(-5000, UAK 검증) → `PromptGuardFilter`(-3000) → `RateLimitFilter`(-1000, 미구현).
- **gateway-backend** 라우팅 설정: `GatewayConfig`(`com.netcom.controlplane.config`)에서 `/api/ai/**` → `${ai.service.url}`, `/api/rag/**` → `${rag.service.url}`.
- **aigateway** 인증: `Filter/AuthFilter`(`com.netcom.controlplane.Filter`)는 `X-API-Key` 존재 여부만 확인, 실제 검증은 gateway-backend가 앞단에서 수행. 벤더별 API 키는 Redis(`UserConfig`) → `KeySelectionService`/`KeyDecryptService`로 복호화 후 사용.
- **rag-storage-service** consumer(워커) 프로필: `rag.app.role=consumer`는 `server.port=0`으로 HTTP 자체가 없어 MCP·게이트웨이 대상에서 원천 제외.
- 미확정: MCP 게이트웨이 제품 선택(오픈소스 mcp-context-forge vs 상용 API Connect), 서비스 계정 UAK 발급 절차, 바이너리 응답 매핑 스펙, 레이트리밋 정책 소유 지점.
