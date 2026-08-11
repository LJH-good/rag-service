# RAG API + LangChain 설정 템플릿

`rag-storage-service`에서 LangChain PCC 위임 시 바로 쓸 수 있는 환경변수 템플릿이다.  
QA 오케스트레이션은 [`ai-connect-qa-orchestration-spec.md`](./ai-connect-qa-orchestration-spec.md) 참고.

## 1) Consumer 인스턴스용 (`dev-consumer` / `rag.app.role=consumer`)

PCC를 LangChain으로 위임하려면 아래를 설정한다.

```env
# ---- PCC delegation ----
RAG_PCC_LANGCHAIN_ENABLED=true
RAG_PCC_LANGCHAIN_BASE_URL=http://langchain-service:8000
RAG_PCC_LANGCHAIN_INVOKE_PATH=/api/internal/rag/pcc/ingest
RAG_PCC_PRESIGN_EXPIRY_SECONDS=3600
RAG_PCC_LANGCHAIN_TIMEOUT_MS=120000
RAG_PCC_LANGCHAIN_MAX_IN_MEMORY_SIZE_BYTES=33554432
```

비위임(기존 PARSE/CLEAN/CHUNK 로컬 파이프라인) 시:

```env
RAG_PCC_LANGCHAIN_ENABLED=false
```

## 2) API / Consumer — Gateway (AIG 경유)

RAG Storage는 AIG URL을 직접 설정하지 않는다. **`GATEWAY_URL`** 만 지정한다.

```env
# Control Plane Gateway — 임베딩(TX-C)·QA(TX-A) 공통
GATEWAY_URL=http://127.0.0.1:9002

# 임베딩 경로의 {aiServiceName} (Gateway /api/ai/{name}/embedding/request)
RAG_EMBEDDING_AI_SERVICE_NAME=openai
```

## 3) 공통 권장값

```env
# DB
# RAG 전용 Postgres: rag-dev/rag-postgres-external NodePort 30544
# NodePort는 어느 노드 IP로 열려 있어도 동일 서비스. 마스터(예: 192.168.0.111) 대신 워커(예: worker3 192.168.0.237)로 붙는 경우가 많음.
# 30543(database-dev/postgres-external)와 포트가 다름 — 혼동 주의
RAG_DEV_DATASOURCE_URL=jdbc:postgresql://192.168.0.237:30544/ragdb
RAG_DEV_DATASOURCE_USERNAME=rag_user
RAG_DEV_DATASOURCE_PASSWORD=init123!

# 추적
# GW -> API -> LangChain으로 X-Transaction-Id 전달 권장
```

## 4) PCC 벤치 테스트용 (`PccLangchainBenchmarkTest`)

```env
RAG_PCC_BENCH_BASE_URL=http://127.0.0.1:8000
RAG_PCC_BENCH_OBJECT_URL_TEMPLATE=http://127.0.0.1:9001/{file}
RAG_PCC_BENCH_PATH=/api/internal/rag/pcc/ingest
RAG_PCC_BENCH_EXTS=csv
RAG_PCC_BENCH_REPEAT=1
RAG_PCC_BENCH_MAX_CHARS=4000
RAG_PCC_BENCH_OVERLAP=400
RAG_PCC_BENCH_MIN_CHARS=200
```

## 5) 운영 체크

- `RAG_PCC_LANGCHAIN_BASE_URL`는 LB/Service DNS로 지정한다.
- 대용량 PCC 응답이 큰 경우, 호출 클라이언트(WebClient) `maxInMemorySize`를 충분히 크게 설정한다.
- timeout은 GW->API와 API->LangChain을 분리해서 관리한다.
