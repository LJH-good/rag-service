#!/bin/bash
# LangChain Service를 Docker로 실행 (원격 Qdrant 사용)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "🚀 Docker Desktop 확인 중..."
if ! docker info > /dev/null 2>&1; then
    echo "❌ Docker Desktop이 실행 중이 아닙니다. 먼저 Docker Desktop을 실행해주세요."
    exit 1
fi

echo "📦 Docker Compose 실행 중..."
cd "$PROJECT_ROOT"
docker compose -f docker-compose.local-e2e.yml up -d

echo "⏳ 서비스 시작 확인 중..."
sleep 3

# LangChain Service 상태 확인
echo "🔍 LangChain Service 상태 확인..."
if curl -s http://localhost:8000/health > /dev/null 2>&1; then
    echo "✅ LangChain Service 정상 (http://localhost:8000)"
else
    echo "⚠️  LangChain Service 준비 중... (몇 초 더 기다려주세요)"
fi

echo ""
echo "✨ 서비스 시작 완료!"
echo ""
echo "📍 접근 주소:"
echo "   - LangChain Service: http://localhost:8000"
echo "   - Qdrant (원격): docker-compose.local-e2e.env의 QDRANT_URL 참조"
echo ""
echo "💡 팁:"
echo "   - 로그 확인: docker compose -f docker-compose.local-e2e.yml logs -f"
echo "   - 중지: docker compose -f docker-compose.local-e2e.yml down"
