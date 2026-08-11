# 로컬 E2E 통합 실행 가이드 (RAG Storage + LangChain + AI Gateway)

**배포 전에 로컬 PC에서** 아래를 한 번에 검증할 때 쓰는 가이드다.

1. **파일 업로드** → MinIO 원본 + DB Job 생성  
2. **Consumer**가 LangChain **PCC** → AI Gateway **임베딩** → **Qdrant** 적재까지 수행  
3. **QA** 호출로 retrieve → Gateway → AIG 오케스트레이션 답변 확인  

가정: 레포가 모두 `C:\workspace\` 아래에 있다.

| 레포 | 경로 |
|------|------|
| RAG Storage (API/Consumer) | `C:\workspace\rag-storage-service` |
| LangChain (PCC/QA) | `C:\workspace\langchain-service` |
| Control Plane Gateway | `C:\workspace\gateway-backend` |
| AI Gateway (임베딩·채팅·QA 오케스트레이션) | `C:\workspace\aigateway` |

---

## 0. 배포 전 로컬에서 꼭 알아둘 것

| 단계 | 어느 프로세스가 하는 일 |
|------|-------------------------|
| 업로드 | **`dev-api`** (`rag.app.role=api`) — MinIO 업로드, Job을 `PCC` 대기로 생성 |
| PCC·임베딩·벡터 적재 | **`dev-consumer`** (`rag.app.role=consumer`) — **API만 켜서는 진행되지 않는다.** Consumer가 LangChain PCC → EMBED(게이트웨이) → UPSERT(Qdrant)를 틱마다 처리한다 |
| QA | **`dev-api`** — `POST .../retrieve` (citations) 후 AI Gateway 오케스트레이션. 상세: [`ai-connect-qa-orchestration-spec.md`](./ai-connect-qa-orchestration-spec.md) |

비밀번호·`MINIO_ACCESS_KEY` 등은 **GitOps `common-secret.yaml`에 정본이 있으면**, 배포 전 로컬에서는 그 값을 **터미널 `$env:`에만** 붙여 넣어 쓰면 된다. 레포/문서에 새로 적어 넣거나 커밋하지 않는다.

---

## 1. 포트·역할 맵 (기본값)

| 서비스 | 기본 포트 | 비고 |
|--------|-----------|------|
| LangChain (uvicorn) | **8000** | `README.md`: `/health`, `/ready` |
| Control Plane Gateway | **9002** | `gateway-backend` — RAG → AIG **유일** 진입 (`/api/ai/**`, `/api/rag/**`) |
| AI Gateway (Spring) | **9001** | `aigateway` 백엔드. Gateway가 프록시; **RAG가 직접 호출하지 않음** |
| RAG Storage **API** | **8080** | `application-dev-api.yml` |
| RAG Storage **Consumer** | **8081** 권장 | `dev-consumer`는 `server.port: 0` 이므로 로컬 E2E 시 `SERVER_PORT=8081` 등으로 고정 권장 |
| PostgreSQL / MinIO / Qdrant / Redis | **§2 표** 참고 | 호스트·포트는 설치 방식에 따라 다름; env/JDBC로 맞춘다 |

**주의:** MinIO 콘솔/API가 `9001`을 쓰는 구성이면 **AI Gateway(`aigateway`)** 와 포트가 겹칠 수 있다. MinIO를 다른 포트로 두거나 AIG `server.port`를 바꾼다. RAG Storage는 **`GATEWAY_URL`(기본 `:9002`)** 만 맞추면 되며, AIG 직접 URL은 설정하지 않는다.

---

## 2. 선행 인프라

다음이 **Consumer·API·LangChain·게이트웨이가 접근 가능한 호스트/포트**로 떠 있어야 한다.  
아래 포트는 **로컬 Docker/기본 설치에서 흔한 값**이며, 실제로는 팀 환경에 맞게 바꾼다.

| 구성요소 | 용도 | 흔한 기본 포트 | 비고 |
|----------|------|----------------|------|
| **PostgreSQL** | RAG 메타·Job·청크 메타 | **5432** | `RAG_DEV_DATASOURCE_URL`의 호스트·포트 |
| **MinIO** (S3 API) | 원본 객체 업로드·presigned | **9000** | `MINIO_END_POINT` / `MINIO_PUBLIC_URL`에 반영. 콘솔 UI만 **9001**인 구성도 많음 → §1 주의(AI Gateway와 겹침) |
| **Qdrant** | 벡터 저장(HTTP) | **6333** | `RAG_QDRANT_BASE_URL` (예: `http://127.0.0.1:6333`). gRPC만 쓰는 경우 **6334** 등 별도 |
| **Redis** | `aigateway` 등 | **6379** | `REDIS_HOST` / `REDIS_PORT` (미설정 시 게이트웨이 프로필에 따라 다름) |

- **`MINIO_PUBLIC_URL`**: LangChain이 presigned 대상을 **HTTP GET** 할 수 있어야 한다. 전부 로컬 호스트에서 돌리면 보통 `http://127.0.0.1:9000`(MinIO API 포트) 형태가 된다.
- 스키마/마이그레이션은 팀 표준 절차에 따른다.

---

## 3. 기동 순서

아래 순서를 지키는 것이 디버깅 비용이 가장 적다.

1. **PostgreSQL, MinIO, Qdrant, (Redis)**  
2. **LangChain** (`langchain-service` 루트)

   ```powershell
   Set-Location C:\workspace\langchain-service
   poetry install
   poetry run uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
   ```

   - 확인: `GET http://127.0.0.1:8000/health`  
   - RAG/PCC 사용 시: `GET http://127.0.0.1:8000/ready/rag` (레포 README·`.env` 기준)

3. **AI Gateway** (`aigateway`)

   - 팀에서 쓰는 프로필로 기동 (예: 로컬 Redis면 `local`, 개발 Redis면 `develop` + Redis env).
   - 확인: `GET http://127.0.0.1:9001/actuator/health` (경로는 `management.endpoints.web.base-path` 설정에 따름)

4. **Control Plane Gateway** (`gateway-backend`)

   - `ai.service.url` → AIG `:9001`, `rag.service.url` → RAG API 포트로 맞춘다.
   - 확인: Gateway 경유 AIG 헬스 — `GET http://127.0.0.1:9002/actuator/health` (Gateway 자체) 및 `GET http://127.0.0.1:9002/api/ai/openai/...` 라우트 동작

5. **RAG Storage — Consumer** (`dev-consumer`)

   - LangChain·MinIO public URL·Qdrant·**`GATEWAY_URL`** 이 consumer JVM에서 모두 도달 가능해야 한다.

6. **RAG Storage — API** (`dev-api`)

   - 업로드·QA는 API 인스턴스에서 처리한다.

---

## 4. 환경변수 템플릿 (PowerShell)

### DEV / GitOps (단일 소스)

클러스터에 올린 **RAG API·Consumer**는 보통 GitOps에서 env를 주입한다. 아래는 **이미 레포에 정의된 위치**이며, **`MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`·DB 비밀번호·`API_KEY_SECRET` 등은 `common-secret`에 모아 두는 형태**다.

| 구분 | GitOps 경로 (워크스페이스 기준) |
|------|--------------------------------|
| 공통 Secret (`MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `RAG_DEV_DATASOURCE_PASSWORD` 등) | `gitops/dev-config/common/common-secret.yaml` |
| RAG 비밀 아닌 설정(MinIO URL, Qdrant, LangChain URL, **`GATEWAY_URL`** 등) | `gitops/dev-config/rag-storage-service/values-consumer.yaml` 의 `appConfig.data` (consumer) / `values-api.yaml` 의 `appConfig.data` (api) |

배포 전 로컬에서 **Gradle로 API·Consumer를 띄울 때**는 Kubernetes가 env를 주입하지 않으므로, 아래 **§4-A 이후** 블록처럼 `$env:`로 직접 맞춘다. 이때 MinIO/DB 키는 **`gitops/dev-config/common/common-secret.yaml`에 있는 값을 로컬 터미널에만 복사**해 써도 된다. 클러스터에 올릴 때의 정본은 여전히 GitOps다.

### 4-A) 공통 (DB, MinIO, Qdrant) — 순수 로컬 JVM 예시

값은 로컬/사내 환경에 맞게 바꾼다. **플레이스홀더는 GitOps에 없는 “맨 로컬”용이다.**

```powershell
$env:RAG_DEV_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5432/ragdb"
$env:RAG_DEV_DATASOURCE_USERNAME = "rag_user"
$env:RAG_DEV_DATASOURCE_PASSWORD = "__CHANGE_ME__"

$env:MINIO_END_POINT = "http://127.0.0.1:9000"
$env:MINIO_PUBLIC_URL = "http://127.0.0.1:9000"
$env:MINIO_ACCESS_KEY = "__CHANGE_ME__"
$env:MINIO_SECRET_KEY = "__CHANGE_ME__"
$env:MINIO_BUCKET = "rag"
$env:RAG_STORAGE_KEY_PREFIX = "rag"

$env:RAG_QDRANT_BASE_URL = "http://127.0.0.1:6333"
$env:RAG_QDRANT_COLLECTION = "rag_chunks"
$env:RAG_QDRANT_TIMEOUT_MS = "30000"
$env:RAG_QDRANT_TOP_K_DEFAULT = "5"
```

### 4-B) Control Plane Gateway ↔ 임베딩·QA

RAG Storage는 AIG를 **직접 호출하지 않는다**. Consumer·API 모두 **`rag.gateway.base-url`** (`GATEWAY_URL`)로 Gateway `:9002`에 붙고, Gateway가 `/api/ai/**`를 AIG로 프록시한다.

| 클라이언트 | 경로 |
|-----------|------|
| `AiEmbeddingClient` (Consumer EMBED, API retrieve) | `POST /api/ai/{aiServiceName}/embedding/request` |
| `AiQaClient` (API QA) | `POST /api/ai/{aiServiceName}/qa` |

```powershell
$env:GATEWAY_URL = "http://127.0.0.1:9002"
```

(`application-*-*.yml`의 `rag.gateway.base-url`과 동일. **`RAG_AI_SERVICE_BASE_URL` / `rag.ai-service.base-url`은 사용하지 않음.**)

### 4-C) LangChain (PCC / QA 위임)

**Consumer 터미널:**

```powershell
$env:RAG_PCC_LANGCHAIN_ENABLED = "true"
$env:RAG_PCC_LANGCHAIN_BASE_URL = "http://127.0.0.1:8000"
$env:RAG_PCC_LANGCHAIN_INVOKE_PATH = "/api/internal/rag/pcc/ingest"
$env:RAG_PCC_PRESIGN_EXPIRY_SECONDS = "3600"
$env:RAG_PCC_LANGCHAIN_TIMEOUT_MS = "120000"
$env:RAG_PCC_LANGCHAIN_MAX_IN_MEMORY_SIZE_BYTES = "33554432"
```

### 4-D) 기타

```powershell
$env:RAG_ENV = "dev"
```

---

## 5. 프로세스 기동 예시 (Gradle)

**Consumer** (별 터미널, 위 공통+PCC+게이트웨이 env 설정 후):

```powershell
Set-Location C:\workspace\rag-storage-service
$env:SERVER_PORT = "8081"
.\gradlew.bat bootRun --args="--spring.profiles.active=dev-consumer"
```

**API**:

```powershell
Set-Location C:\workspace\rag-storage-service
# 동일한 DB/MinIO/Qdrant env
.\gradlew.bat bootRun --args="--spring.profiles.active=dev-api"
```

### 5.1 GitOps 기반 자동 env + (선택) 창 띄우기

레포 루트에 다음이 있다.

- `docker-compose.local-e2e.yml` — 로컬 **Qdrant**만 띄움 (Docker Desktop 필요).
- `scripts/run-local-e2e.ps1` — 위와 동일 소스로 `$env:` 설정, `127.0.0.1:8000` LangChain은 health OK 시 로컬로 덮어씀. **Control Plane Gateway는 `GET http://127.0.0.1:9002/actuator/health` 가 2xx일 때만** `GATEWAY_URL` 을 로컬로 덮어씀, `build/set-local-e2e-env.ps1` 생성.

```powershell
Set-Location C:\workspace\rag-storage-service
# 1) Docker 켠 뒤 Qdrant 기동 (선택이지만 UPSERT까지 하려면 필요)
docker compose -f docker-compose.local-e2e.yml up -d

# 2) env 적용 + 스크립트만
.\scripts\run-local-e2e.ps1

# 3) 같은 터미널에서 Consumer / API 를 새 창으로 bootRun
.\scripts\run-local-e2e.ps1 -StartRag
```

`build/set-local-e2e-env.ps1` 은 `build/` 아래라 **gitignore** 대상(비밀 포함). 다른 터미널에서는:

```powershell
Set-Location C:\workspace\rag-storage-service
. .\build\set-local-e2e-env.ps1
$env:SERVER_PORT = "8081"
.\gradlew.bat bootRun --args="--spring.profiles.active=dev-consumer"
```

**실제 실행 시 확인한 점:** `common-secret` 의 `RAG_DEV_DATASOURCE_USERNAME` / `RAG_DEV_DATASOURCE_PASSWORD` 가 DB와 다르면 인증 실패로 부팅이 멈춘다. **RAG DB는 `rag-dev/rag-postgres-external` NodePort `30544`** 이고, **`database-dev/postgres-external` 의 `30543`은 다른 인스턴스**이므로 JDBC URL 포트를 혼동하지 말 것.

### 5.2 PCC → EMBED → UPSERT 자동 검증 (`run-full-e2e-verify.ps1`)

한 번에 **Consumer + API** Gradle Job을 띄우고, **업로드(충분한 텍스트)** 후 Consumer 로그에서 아래를 **같은 데드라인 안에서** 폴링한다.

- `RAG_PCC` + `PCC ok`
- `RAG_EMBED` + `embeddings part uploaded`
- `RAG_UPSERT` + `upsert done` 또는 `handle success`

**선행 조건:** LangChain `http://127.0.0.1:8000` (PCC), 로컬 **Control Plane Gateway**(`:9002`) + **aigateway**(`--spring.profiles.active=local`, `:9001` 등). Consumer·API의 임베딩·QA는 **`GATEWAY_URL` 경유** + **aiGateway UAK**(`X-API-Key`)로 호출된다. **Qdrant**는 로컬 Docker이거나, 클러스터에 배포된 인스턴스면 `.\scripts\run-full-e2e-verify.ps1 -ClusterQdrant` 로 GitOps `values-consumer.yaml` 의 `RAG_QDRANT_BASE_URL` 을 쓴다(로컬 Consumer 가 그 URL에 네트워크로 닿아야 함).

```powershell
Set-Location C:\workspace\rag-storage-service
# Qdrant 로컬이면: docker compose -f docker-compose.local-e2e.yml up -d

.\scripts\run-full-e2e-verify.ps1
# 클러스터 Qdrant(로컬 PC에서 *.svc DNS 안 될 수 있음 → NodePort 또는 포트포워드 URL):
#   .\scripts\run-full-e2e-verify.ps1 -QdrantBaseUrl "http://127.0.0.1:6333" -SkipDocker -WaitSeconds 240
# GitOps values-consumer 의 클러스터 URL 그대로: .\scripts\run-full-e2e-verify.ps1 -ClusterQdrant -SkipDocker -WaitSeconds 240
# 포트 충돌 시: .\scripts\run-full-e2e-verify.ps1 -ApiPort 8082 -ConsumerPort 8081 -WaitSeconds 240
```

`PCC_EMPTY` 등이 나오면 LangChain이 청크를 주지 않은 것이므로, 업로드 본문·LangChain 설정·presigned URL GET 가능 여부를 본다. 스크립트는 **업로드 응답의 `jobId`** 가 Consumer 로그의 `[RAG_PCC][jobId]` 등과 일치할 때만 성공·조기 실패를 판정해, 큐에 남아 있던 다른 Job 로그에 오판하지 않는다.

### 5.3 전체 테스트 (Gradle + 파이프라인)

```powershell
Set-Location C:\workspace\rag-storage-service
# Qdrant NodePort 등 (기본값: http://192.168.0.237:30633 또는 $env:RAG_E2E_QDRANT_BASE_URL)
.\scripts\run-comprehensive-rag-tests.ps1 -SkipDocker -QdrantBaseUrl "http://192.168.0.237:30633"
```

순서: `gradlew test` → `run-full-e2e-verify.ps1`.

---

## 6. 스모크 테스트

**업로드만 하고 Consumer를 안 띄운 상태**면 Job이 `PCC`에 멈추고, 임베딩·Qdrant 적재가 되지 않아 QA 검색 결과도 없다. **배포 전 로컬 E2E는 Consumer 터미널을 반드시 같이 둔다.**

### 6-1) 업로드

- **POST** `http://localhost:8080/api/rag/{aiServiceName}/documents/upload`  
- **Headers:** `X-API-Key`, `X-Company-Id`, `X-User-Id`, **`X-Transaction-Id`** (UUID)  
- **Multipart:** `file` (필수), `categoryId` / `scope` / `title` (선택)

`curl.exe` 예시 (`aiServiceName=openai`, 작은 텍스트 파일):

```powershell
$tid = [guid]::NewGuid().ToString()
curl.exe -s -S -X POST "http://localhost:8080/api/rag/openai/documents/upload" `
  -H "X-API-Key: YOUR_KEY" `
  -H "X-Company-Id: company-1" `
  -H "X-User-Id: user-1" `
  -H "X-Transaction-Id: $tid" `
  -F "file=@C:\path\to\small.txt" `
  -F "categoryId=manual"
```

성공 시 응답에 `documentId` / `jobId` 등이 온다. 이후 **Consumer 로그**에서 `RAG_PCC` → `RAG_EMBED` → `RAG_UPSERT` 순으로 진행되는지 본다.

### 6-2) RAG 검색 (`/retrieve`)

- **POST** `http://localhost:8080/api/rag/{aiServiceName}/retrieve`
- **Headers:** `X-API-Key`, `X-User-No`, **`X-Transaction-Id`**
- **Body:** `{"searchQuery":"질문 본문","categoryId":"..."}`

QA 전체 플로우( AI Gateway 오케스트레이션 )는 [`ai-connect-qa-orchestration-spec.md`](./ai-connect-qa-orchestration-spec.md) 참고.

---

## 7. 성공 판정 체크리스트

- [ ] 업로드 API **200**, 문서 상태가 최종적으로 성공 정책에 도달하는지(DB 또는 API 조회)  
- [ ] Job 단계가 **PCC → EMBED → UPSERT** 로 진행·완료  
- [ ] LangChain이 **`MINIO_PUBLIC_URL` presigned 대상**을 GET 할 수 있음(방화벽/DNS)  
- [ ] **Control Plane Gateway** `:9002` 및 AIG 백엔드 `:9001` 기동, RAG에서 **`GATEWAY_URL`** 로 임베딩·QA 라우트 도달  
- [ ] **Qdrant**에 포인트가 쌓이거나, upsert 로그에 실패 없음  
- [ ] (선택) `POST /retrieve` 로 citations 수신

---

## 8. 트러블슈팅 (요약)

| 증상 | 점검 |
|------|------|
| PCC 타임아웃 / 본문 잘림 | `RAG_PCC_LANGCHAIN_TIMEOUT_MS`, `RAG_PCC_LANGCHAIN_MAX_IN_MEMORY_SIZE_BYTES` |
| LangChain이 파일 못 받음 | `MINIO_PUBLIC_URL`이 LangChain이 있는 머신에서 열리는지, presigned 만료 |
| 임베딩·QA 실패 | RAG의 **`GATEWAY_URL`**(`rag.gateway.base-url`)이 Control Plane Gateway `:9002`인지, Gateway→AIG 라우트·AIG 헬스·Redis |
| QA 400 / IllegalArgumentException | **`X-Transaction-Id` 누락** 여부 |
| 포트 충당당 | MinIO `9001` vs `aigateway` `9001` 등 |
| 업로드만 되고 PCC/EMBED 없음 | **`dev-consumer` 미기동** 또는 DB·MinIO env가 API와 Consumer가 서로 다른 DB를 보고 있음 |

---

## 9. 관련 문서 (이 레포)

- [rag-langchain-env-template.md](./rag-langchain-env-template.md) — env 키 요약  
- [RAG-Storage-LangChain-Integration.md](./RAG-Storage-LangChain-Integration.md) — PCC 계약·벤치  
- [langchain-cutover-checklist.md](./langchain-cutover-checklist.md) — DEV 컷오버 항목  

LangChain 레포 쪽 상세 예시: `C:\workspace\langchain-service\docs\RAG-Storage-LangChain-PCC-QA-연동가이드.md`, `docs\QA-PCC-API-Examples.md`
