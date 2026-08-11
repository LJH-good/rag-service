# RAG-Storage-Service ↔ LangChain-Service (PCC) 연동

LangChain-Service 레포의 구현(`POST /api/internal/rag/pcc/ingest`, 타이밍 헤더 등)과 **이 레포(rag-storage-service)** 설정·테스트를 맞춘 요약이다.

## 역할

| 구분 | 담당 |
|------|------|
| **LangChain-Service** | PCC ingest HTTP API, `objectUrl` GET → parse/clean/chunk, 타이밍 헤더·에러 JSON |
| **RAG-Storage-Service (이 레포)** | presigned URL 발급, 컨슈머에서 LangChain으로 PCC 위임, JUnit 벤치·CSV 리포트 |

## URL·경로 계약

| 항목 | 기본값 (이 레포 `application.yml` / 코드 fallback) |
|------|------------------------------------------------------|
| PCC ingest 경로 | **`/api/internal/rag/pcc/ingest`** |
| 환경변수로 덮어쓰기 | `RAG_PCC_LANGCHAIN_INVOKE_PATH` (consumer), 벤치 전용 `RAG_PCC_BENCH_PATH` |

경로를 바꾸면 **LangChain-Service 라우터에 동일 경로가 있어야** 한다.

## LangChain PCC 요청 헤더

- **`X-Transaction-Id`**: LangChain 쪽 필수.  
  - 컨슈머: `RagPccWorker`가 `jobId`를 넣어 `LangchainPccClient`가 전송한다.  
  - JUnit: `PccLangchainBenchmarkTest`가 벤치용 ID를 넣는다.

## 이 레포에서 할 일 (체크리스트)

### 1) LangChain 기동·헬스

LangChain 베이스 URL이 스토리지/테스트 머신에서 접근 가능해야 한다. (상대 `docs/health` 등은 LangChain 레포 문서 따름.)

### 2) 컨슈머 연동

```text
RAG_PCC_LANGCHAIN_ENABLED=true
RAG_PCC_LANGCHAIN_BASE_URL=http://호스트:포트
# 선택 — 기본과 같으면 생략
# RAG_PCC_LANGCHAIN_INVOKE_PATH=/api/internal/rag/pcc/ingest
```

### 3) JUnit 벤치 `PccLangchainBenchmarkTest`

벤치 파일은 **HTTP GET으로 LangChain이 내려받을 수 있는 URL**이어야 한다 (presigned MinIO 등).

PowerShell 예:

```powershell
$env:RAG_PCC_BENCH_BASE_URL = "http://127.0.0.1:8000"
$env:RAG_PCC_BENCH_OBJECT_URL_TEMPLATE = "https://your-minio-or-cdn/bench/{ext}/{file}"
# 선택: $env:RAG_PCC_BENCH_PATH, $env:RAG_PCC_BENCH_EXTS

Set-Location <rag-storage-service 루트>
.\gradlew.bat test --tests "com.init.worker.langchain.PccLangchainBenchmarkTest"
```

- `{ext}`, `{file}` 치환: 예) `bench/pdf/1mb_text.pdf` 로 GET 가능해야 함.
- 벤치 CSV 출력: **`build/reports/pcc-langchain-benchmark-*.csv`**

## 타이밍 헤더 (벤치·관측)

LangChain이 아래 응답 헤더를 주면 `PccLangchainBenchmarkTest`가 CSV에 기록한다.

- `X-Pcc-Download-Ms`
- `X-Pcc-Parse-Clean-Chunk-Ms`
- `X-Pcc-Total-Ms`
- `X-Pcc-Chunk-Count`

## 트러블슈팅 (짧게)

| 증상 | 점검 |
|------|------|
| timeout | LangChain `STREAM_TIMEOUT_SECONDS`, presigned 만료, WebClient/리버스프록시 타임아웃 |
| 다운로드 실패 | `objectUrl` 오타, TLS, 방화벽, LangChain이 GET 가능한 네트워크 |
| 404 | `invoke-path` / `RAG_PCC_BENCH_PATH` 와 LangChain 라우트 불일치 |
| 청크 0 | `minChars` 과대, 본문 비어 있음 |

## LangChain-Service 레포 쪽 참고 (파일은 상대 레포 기준)

- PCC 라우터·헤더·스키마: `app/langchain_service/api/v1/pcc.py`, `app/schema/pcc.py` 등
- 예시·스크립트: `docs/QA-PCC-API-Examples.md`, `scripts/pcc_benchmark_report.py`

로컬에서 RAG Storage + LangChain + AI Gateway까지 한꺼번에 띄우는 절차는 [local-e2e-integration-runbook.md](./local-e2e-integration-runbook.md)를 본다.
