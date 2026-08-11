# LangChain HA Architecture

## 목표

- LangChain 도입을 전제로 RAG 경로를 고가용성(HA)으로 운영한다.
- API 계층은 2중화, LangChain 모듈은 3중화한다.
- 대용량 문서(PCC/embedding)에서도 장애 전파 없이 처리 가능하게 만든다.

## 상위 구조

- **Client**: `admin`, `portal`
- **Gateway (GW)**: 외부 진입점, 인증 전처리/라우팅
- **RAG API**: 2 replicas
- **LangChain Module**: 3 replicas
- **Vector DB**: 공용 벡터 저장소

요청 흐름(기본):

1. `admin/portal -> GW`
2. `GW -> RAG API(2중화 LB)`
3. `RAG API -> LangChain Module(3중화 LB)`
4. `LangChain Module <-> Vector DB`
5. 응답 역방향 반환

## 컴포넌트 역할

### GW

- TLS 종료, 라우팅, rate limit
- trace header(`X-Transaction-Id`) 전달
- API 장애 시 건강한 인스턴스로 우회

### RAG API (2 replicas)

- 인증/권한, 요청 검증, 경로 라우팅
- LangChain 호출 오케스트레이션
- 에러 표준화, 타임아웃/재시도 제어
- stateless 유지 (세션 고정 금지)

### LangChain Module (3 replicas)

- PCC(parse/clean/chunk), embedding, retrieval, answer 생성
- stateless 수평 확장
- 내부 모델/벡터DB 연동

### Vector DB

- chunk embedding upsert/search
- 컬렉션/인덱스 스키마 일관성 유지

## 배포/운영 원칙

### 공통

- `readinessProbe` / `livenessProbe` 분리
- `PodDisruptionBudget` 설정
- 리소스 request/limit 명시
- 무중단 롤링 업데이트

### 권장 초기 replica / HPA

- RAG API: `minReplicas=2`, `maxReplicas=6`
- LangChain: `minReplicas=3`, `maxReplicas=10`

오토스케일 지표(권장):

- CPU / Memory
- P95 latency
- (비동기 큐 사용 시) queue depth

## 장애/성능 대응 포인트

- **Trace 전파**: `X-Transaction-Id`를 GW -> API -> LangChain 전 구간 유지
- **응답 버퍼 한도**: 대용량 응답 대비 클라이언트 `maxInMemorySize` 설정값 환경변수화
- **타임아웃 계층화**: GW→API, API→LangChain 각각 별도 관리
- **재시도 규칙**: idempotent 요청만 재시도
- **대용량 작업 분리**: PCC/embedding은 가능하면 비동기 큐로 분리

## 환경변수 매핑 (rag-storage-service 기준)

복붙용 템플릿은 `docs/rag-langchain-env-template.md` 참고.

### PCC 경로 위임

- `RAG_PCC_LANGCHAIN_ENABLED`
- `RAG_PCC_LANGCHAIN_BASE_URL`
- `RAG_PCC_LANGCHAIN_INVOKE_PATH`

### PCC 벤치/테스트

- `RAG_PCC_BENCH_BASE_URL`
- `RAG_PCC_BENCH_OBJECT_URL_TEMPLATE`
- `RAG_PCC_BENCH_PATH`
- `RAG_PCC_BENCH_EXTS`
- `RAG_PCC_BENCH_REPEAT`
- `RAG_PCC_BENCH_MAX_CHARS`
- `RAG_PCC_BENCH_OVERLAP`
- `RAG_PCC_BENCH_MIN_CHARS`

## 단계별 적용 계획 (권장)

1. **Stage 1**: API 2중화 + LangChain 1개(기능 검증)
2. **Stage 2**: LangChain 3중화 + HPA 적용
3. **Stage 3**: PCC/embedding 비동기화 + 큐 기반 백프레셔
4. **Stage 4**: 부하 테스트 기준으로 autoscaling/pool/timeout 튜닝

## 검증 체크리스트

- [ ] API 1개 다운 시 요청 성공
- [ ] LangChain 1개 다운 시 요청 성공
- [ ] 대용량 CSV/PDF 입력 시 timeout/메모리 오류 없음
- [ ] `X-Transaction-Id` 로그 추적 가능
- [ ] P95 latency / error rate 대시보드 확인 가능

DEV cutover 상세 실행 항목은 `docs/langchain-cutover-checklist.md` 참고.

