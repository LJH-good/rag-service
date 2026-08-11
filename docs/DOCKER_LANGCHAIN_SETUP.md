# IntelliJ에서 LangChain Service Docker로 실행하기

## 📋 개요

이 가이드는 **LangChain Service**를 Docker로 실행하고 **원격 Qdrant**에 붙이는 방법을 설명합니다.

### 포트 구성
- **LangChain Service**: 8000
- **Qdrant**: 원격 인스턴스 (docker-compose.local-e2e.env에서 설정)
- **RAG Worker API**: 8082 (로컬)
- **RAG Worker Consumer**: 8083 (로컬)

---

## 🚀 빠른 시작

### 1️⃣ Docker Desktop 실행
Windows/Mac에서 Docker Desktop을 먼저 실행하세요.

### 2️⃣ 스크립트 실행

**Windows (PowerShell):**
```powershell
.\scripts\start-langchain-docker.ps1
```

**macOS/Linux (Bash):**
```bash
bash scripts/start-langchain-docker.sh
```

### 3️⃣ 서비스 확인
```bash
# 실행 중인 컨테이너 확인 (LangChain Service만 실행 중)
docker ps

# 로그 보기
docker compose -f docker-compose.local-e2e.yml logs -f langchain-service

# 원격 Qdrant 연결 확인
curl http://192.168.0.116:6333/health
```

---

## 🎯 IntelliJ에서 Run Configuration 설정

### 방법 1: Docker Compose Configuration (권장)

**Step 1:** `Run` → `Edit Configurations`

**Step 2:** `+` → `Docker Compose` 선택

**Step 3:** 다음과 같이 설정:
```
Name: Local E2E with LangChain
Compose file: docker-compose.local-e2e.yml
Services: (모든 서비스 선택)
Environment variables: 필요시 추가
```

**Step 4:** `OK` 클릭

### 방법 2: 수동 실행

프로젝트 루트에서:
```bash
docker compose -f docker-compose.local-e2e.yml up -d
```

---

## 📝 환경 설정

### docker-compose.local-e2e.env 파일
`docker-compose.local-e2e.yml`에서 환경 변수를 로드합니다.

필요시 다음을 수정하세요:
```env
# Qdrant 연결 (원격 인스턴스)
QDRANT_URL=http://192.168.0.116:6333

# Control Plane Gateway (QA/RAG 임베딩·LLM — Gateway :9002 경유, 키는 요청 X-API-Key)
GATEWAY_URL=http://host.docker.internal:9002

# Redis (세션 저장소, 선택사항)
# REDIS_URL=redis://redis:6379/0
```

---

## 🔍 헬스 체크

### LangChain Service (로컬)
```bash
curl http://localhost:8000/health
```

### Qdrant (원격)
```bash
# docker-compose.local-e2e.env에 설정된 QDRANT_URL 확인
curl http://192.168.0.116:6333/health
```

---

## 🛑 서비스 중지

```bash
# 컨테이너 중지 및 삭제
docker compose -f docker-compose.local-e2e.yml down

# 데이터 포함 완전 제거
docker compose -f docker-compose.local-e2e.yml down -v
```

---

## 🐛 문제 해결

### 포트 충돌
```bash
# 포트 사용 확인 (Windows)
netstat -ano | findstr :8000
netstat -ano | findstr :6333

# 포트 사용 확인 (macOS/Linux)
lsof -i :8000
lsof -i :6333
```

### 컨테이너 로그 확인
```bash
# 특정 서비스 로그
docker compose -f docker-compose.local-e2e.yml logs langchain-service -f

# 모든 로그
docker compose -f docker-compose.local-e2e.yml logs -f
```

### 이미지 재빌드
```bash
docker compose -f docker-compose.local-e2e.yml build --no-cache langchain-service
```

---

## 🔄 RAG Worker 설정

### RAG Worker API (로컬)에서 LangChain Service 접근

`application-local-api.yml` 확인:
```yaml
rag:
  langchain-service:
    enabled: true
    base-url: http://localhost:8000  # Docker 외부에서 접근
```

### Docker 내부 통신
Docker 내에서 다른 서비스에 접근할 때:
```yaml
base-url: http://langchain-service:8000  # 컨테이너 이름 사용
```

---

## 📚 관련 파일

- `docker-compose.local-e2e.yml` - Docker Compose 설정
- `docker-compose.local-e2e.env` - 환경 변수
- `../langchain-service/Dockerfile` - LangChain Service 이미지
- `scripts/start-langchain-docker.sh` - 자동 실행 스크립트 (Linux/Mac)
- `scripts/start-langchain-docker.ps1` - 자동 실행 스크립트 (Windows)

---

## 💡 팁

### 로그 모니터링
```bash
# 실시간 로그 확인
docker compose -f docker-compose.local-e2e.yml logs -f

# 최근 100줄만 보기
docker compose -f docker-compose.local-e2e.yml logs --tail=100
```

### 컨테이너 접속
```bash
# LangChain Service 접속
docker compose -f docker-compose.local-e2e.yml exec langchain-service /bin/bash
```

### LangChain Service 실행
```bash
docker compose -f docker-compose.local-e2e.yml up -d langchain-service
```

---

## 🤔 자주 묻는 질문

**Q: RAG Worker를 로컬에서 실행하고 LangChain Service는 Docker에서 실행할 수 있나요?**

A: 네! RAG Worker를 IntelliJ에서 직접 실행하고, LangChain Service만 Docker로 실행할 수 있습니다. 
이 경우 `application-local-api.yml`에서:
```yaml
base-url: http://localhost:8000  # Docker 외부이므로 localhost 사용
```

**Q: 원격 Qdrant의 URL을 변경하고 싶어요.**

A: `docker-compose.local-e2e.env`에서 `QDRANT_URL`을 수정하세요:
```env
QDRANT_URL=http://your-qdrant-host:6333
```

**Q: 포트를 다르게 사용하고 싶어요.**

A: `docker-compose.local-e2e.yml`에서 포트 설정을 수정하세요:
```yaml
ports:
  - "9000:8000"  # 호스트:컨테이너
```

**Q: 로컬에서 langchain-service를 개발하고 싶어요.**

A: IntelliJ에서 `langchain-service` 프로젝트를 직접 열어서 실행하는 것을 권장합니다.
다만 `PYTHONPATH`, `REDIS_URL`, `QDRANT_URL` 등을 설정해야 합니다.
