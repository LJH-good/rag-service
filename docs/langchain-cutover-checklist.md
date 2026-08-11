# LangChain Cutover Checklist (DEV)

## 목적

- `rag-storage-service`는 오케스트레이션(호출/상태관리)만 담당
- PCC 처리(parse/clean/chunk) 본체는 LangChain 서비스에서 수행
- DEV 환경에서 단계적으로 cutover

## 1) 역할 분리

### A. `rag-storage-service`(여기서 진행할 일)

- 업로드 ingest 시 시작 step을 `PCC`로 고정
- `RagPccWorker`를 통해 LangChain PCC API 호출
- `X-Transaction-Id` 전달 및 에러 표준화
- 재시도 스케줄러는 `PCC/EMBED/UPSERT`만 처리
- 로컬 PARSE/CLEAN/CHUNK 코드 제거

### B. LangChain 서비스(저쪽에서 진행할 일)

- `POST /api/internal/rag/pcc/ingest` 안정화
- 대용량 파일(10/50/100MB)에서 chunk 응답 안정성 확보
- 필요 시 응답 헤더 제공
  - `X-Pcc-Download-Ms`
  - `X-Pcc-Parse-Clean-Chunk-Ms`
  - `X-Pcc-Total-Ms`
  - `X-Pcc-Chunk-Count`
- scale-out(3 replicas) 시 동작/성능 검증

## 2) GitOps 변경 포인트

아래 항목은 환경별 values/manifest에서 반영 필요.

### `rag-storage-service` (Consumer)

- `RAG_PCC_LANGCHAIN_ENABLED=true`
  - `RAG_PCC_LANGCHAIN_BASE_URL`
  - `RAG_PCC_LANGCHAIN_INVOKE_PATH=/api/internal/rag/pcc/ingest`
  - `RAG_PCC_LANGCHAIN_TIMEOUT_MS`
  - `RAG_PCC_LANGCHAIN_MAX_IN_MEMORY_SIZE_BYTES`

### LangChain 서비스

- replicas: `3` (HPA 있으면 min=3)
- readiness/liveness probe 경로와 초기 지연값 조정
- 요청 크기/응답 크기 제한(Nginx/Ingress 포함) 상향
- timeout(ingress/upstream/gunicorn/uvicorn) 정합성

### Ingress / Gateway

- API 업스트림 2 replicas 라우팅
- 요청 본문/응답 timeout 및 body-size 정책 점검
- `X-Transaction-Id` 헤더 pass-through 확인

## 3) DEV 검증 시나리오

- [ ] 10MB 유형별 CSV(4~5종) 정상 처리
- [ ] 50MB/100MB CSV 세트 정상 처리
- [ ] API pod 1개 down 시 처리 지속
- [ ] LangChain pod 1개 down 시 처리 지속
- [ ] 재시도 스케줄러에서 PCC 실패 건 재처리 확인

## 4) 롤백 전략

- 임시 롤백: `RAG_PCC_LANGCHAIN_ENABLED=false` 는 더 이상 지원하지 않음(로컬 PCC 제거됨).
- 실질 롤백은 배포 단위로 수행:
  - 이전 이미지 태그로 되돌리기
  - LangChain 서비스 장애 시 트래픽 차단/우회 정책 적용
