# LangChain Service를 Docker로 실행 (원격 Qdrant 사용)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

Write-Host "🚀 Docker Desktop 확인 중..." -ForegroundColor Cyan
try {
    docker info | Out-Null
} catch {
    Write-Host "❌ Docker Desktop이 실행 중이 아닙니다. 먼저 Docker Desktop을 실행해주세요." -ForegroundColor Red
    exit 1
}

Write-Host "📦 Docker Compose 실행 중..." -ForegroundColor Cyan
Push-Location $ProjectRoot
docker compose -f docker-compose.local-e2e.yml up -d
Pop-Location

Write-Host "⏳ 서비스 시작 확인 중..." -ForegroundColor Cyan
Start-Sleep -Seconds 3

# LangChain Service 상태 확인
Write-Host "🔍 LangChain Service 상태 확인..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8000/health" -ErrorAction SilentlyContinue
    Write-Host "✅ LangChain Service 정상 (http://localhost:8000)" -ForegroundColor Green
} catch {
    Write-Host "⚠️  LangChain Service 준비 중... (몇 초 더 기다려주세요)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "✨ 서비스 시작 완료!" -ForegroundColor Green
Write-Host ""
Write-Host "📍 접근 주소:" -ForegroundColor Cyan
Write-Host "   - LangChain Service: http://localhost:8000"
Write-Host "   - Qdrant (원격): docker-compose.local-e2e.env의 QDRANT_URL 참조"
Write-Host ""
Write-Host "💡 팁:" -ForegroundColor Cyan
Write-Host "   - 로그 확인: docker compose -f docker-compose.local-e2e.yml logs -f"
Write-Host "   - 중지: docker compose -f docker-compose.local-e2e.yml down"
