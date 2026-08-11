# IBM AI Gateway 제품 도입 설계

> 이 문서는 **IBM이 실제로 판매/제공하는 "AI Gateway" 계열 제품**을 도입했을 때 사내에 이미 있는 기능 중 무엇을 대체할 수 있는지를 다룬다. IBM AI 제품군은 성격이 다른 **두 가지 트랙**으로 나뉘며, 어떤 트랙을 선택하느냐에 따라 도입 제품과 아키텍처가 달라진다.

---

## 한 줄 요약

IBM AI 제품군은 **DataPower 기반 트랙**(온프레미스 Kubernetes, 게이트웨이 거버넌스 중심)과 **watsonx Orchestrate 기반 트랙**(SaaS/클라우드, 에이전트 오케스트레이션 중심)으로 나뉜다. 두 트랙은 대상 계층이 다르므로 경쟁 관계가 아니나, 사내 인프라가 **온프레미스**인 현 상황에서는 트랙 선택에 중요한 제약이 따른다.

---

## 1. IBM AI 제품군 전체 분류

| 제품 | 트랙 | 성격 | 성숙도 | 인프라 요건 |
|---|---|---|---|---|
| **API Connect AI Gateway** | DataPower | LLM API 트래픽 거버넌스·비용 제어·레이트리밋 | 정식 출시, 온프레미스 10.0.8.1+ | DataPower Gateway 필요 |
| **DataPower Interact Gateway** | DataPower | MCP 에이전트 상호작용 트래픽 거버넌스 (A2A는 향후 지원 예정) | 갓 출시 (IBM Think 2026) | DataPower Gateway 필요 |
| **watsonx Orchestrate** | watsonx | AI 에이전트 생성·실행·오케스트레이션, A2A | 정식 출시 | **하이브리드** — 클라우드 + 온프레미스 모두 지원 |
| **watsonx Model Gateway** | watsonx | 멀티벤더 LLM OpenAI 호환 라우팅·로드밸런싱 | watsonx.ai GA, **모델 게이트웨이 기능만 프리뷰** | **모델 게이트웨이 기능 한정 SaaS 전용** (watsonx 플랫폼 자체는 온프레미스 포함) |
| **mcp-context-forge** | 오픈소스 | MCP/A2A/REST 통합 게이트웨이·레지스트리·프록시 | 오픈소스, 프로덕션 적용 가능 | 없음 (컨테이너로 독립 실행) |

---

## 2. 두 트랙 개요

### 트랙 A: DataPower 기반

```
온프레미스 Kubernetes 클러스터
└── DataPower Gateway (Kubernetes Operator로 배포)
    ├── DataPower Interact Gateway  ← MCP 에이전트 트래픽 거버넌스 (A2A 향후 예정)
    └── API Connect AI Gateway      ← HTTP/REST LLM API 거버넌스
```

- **특징**: 온프레미스 Kubernetes 위에서 동작. DataPower Gateway 하나를 설치하면 두 제품을 라이선스 추가만으로 활성화 가능.
- **적합한 환경**: 온프레미스 Kubernetes 환경, 강력한 거버넌스·감사 요건.
- **제약**: DataPower 라이선스 비용, 갓 출시된 DataPower Interact Gateway의 성숙도 리스크.

### 트랙 B: watsonx Orchestrate 기반

```
배포 옵션 1: IBM Cloud SaaS / AWS SaaS
배포 옵션 2: 온프레미스 (Red Hat OpenShift + IBM Software Hub)
└── watsonx Orchestrate     ← 에이전트 생성·실행·A2A 오케스트레이션
    └── watsonx Model Gateway   ← 멀티벤더 LLM 라우팅 (모델 게이트웨이 기능 프리뷰, SaaS 전용)
```

- **특징**: 에이전트를 직접 만들고 실행하는 레이어. watsonx Orchestrate는 IBM Cloud SaaS, AWS SaaS, 온프레미스 3가지 배포 모델을 공식 지원. 온프레미스 배포 기반은 **Red Hat OpenShift + IBM Software Hub**.
- **적합한 환경**: 멀티에이전트 오케스트레이션이 핵심 요건인 환경. 에어갭(air-gapped) 환경 포함 온프레미스 배포 가능.
- **구성**: watsonx Orchestrate 단독 도입 시 LLM 라우팅은 Model Gateway가 아닌 **기존 aigateway가 그대로 담당** — Orchestrate는 에이전트 오케스트레이션 레이어로만 추가되며, 현 사내 LLM 라우팅 구조를 교체하지 않는다. Model Gateway는 별개 제품으로 SaaS 전용·프리뷰 단계이므로 현 시점 도입 대상이 아니다.

---

## 3. 트랙 A: DataPower 기반

> **출처**: [Announcing IBM DataPower Interact Gateway](https://www.ibm.com/new/announcements/datapower-interact-gateway-for-governed-ai-interactions) · [IBM DataPower Gateway 제품 페이지](https://www.ibm.com/products/datapower-gateway) · [DataPower Kubernetes Operator](https://www.ibm.com/docs/en/datapower-operator) · [AI Gateway - IBM API Connect](https://www.ibm.com/products/api-connect/ai-gateway) · [IBM Docs — Using the AI Gateway](https://www.ibm.com/docs/en/api-connect/cloud/10.0.x_saas?topic=definitions-using-ai-gateway-support-apis-ai-applications) · [API Connect on Kubernetes](https://www.ibm.com/docs/en/api-connect/10.0.x?topic=installing-api-connect-kubernetes)

### 3-1. DataPower Interact Gateway 핵심 기능

IBM Think 2026에서 발표된 제품. MCP 에이전트 트래픽을 통제하는 거버넌스 레이어로, 에이전트를 직접 실행하는 게 아니라 **에이전트 간 통신이 오갈 때 그 트래픽을 감시·통제**한다. A2A는 현재 설계 단계이며 향후 지원 예정이다.

- **MCP 프로토콜 네이티브 지원**: 일반 HTTP 래핑이 아니라 프로토콜을 직접 이해해 처리. (A2A는 향후 지원 예정 — IBM 공식 발표 기준 로드맵 단계)
- **에이전트 접근 통제**: 어떤 에이전트가 어떤 툴·모델·API에 접근할 수 있는지 중앙 정책으로 통제.
- **상호작용 감사 로그**: 애플리케이션·에이전트·모델·서비스 간 상호작용을 중앙에서 기록. (공식 자료 기준 "호출 체인 전체 추적"이라는 표현은 미확인, 방향성은 동일)
- **정책 집행**: 에이전트 통신에 데이터 마스킹·레이트리밋·콘텐츠 필터링 일괄 적용.

### 3-2. API Connect AI Gateway 핵심 기능

- **거버넌스**: 정책 집행, 민감정보 마스킹, 접근 제어, 감사 로그를 LLM API 트래픽에 일괄 적용.
- **비용 제어**: 요청 수 또는 **토큰 생성량 기준** 레이트리밋, AI 응답 캐싱.
- **모델 라우팅**: watsonx.ai, OpenAI 등 벤더 API 중앙 관리.
- **분석**: 조직 전체 AI API 사용 현황 내장 대시보드.

### 3-3. 사내 기능 대체 범위

| 기능 | 담당 제품 | 대체 판단 |
|---|---|---|
| MCP 노출 (신규) | DataPower Interact Gateway | **채택** — mcp-context-forge 없이 엔터프라이즈급으로 시작 |
| RateLimitFilter (미구현 스텁) | API Connect AI Gateway | **자체 구축 계획 대체** — 토큰 기반 레이트리밋 기본 제공 |
| 벤더별 LLM 라우팅 (aigateway) | API Connect AI Gateway | **부분 대체** — 라우팅은 대체, BYOK 미지원으로 키 관리는 aigateway 잔류 |
| PromptGuardFilter | 해당 없음 | **대체 불가** — 외부 ML 서비스·한국어 패턴·ES 로그 구조, 현 구조 유지 |
| admin-front 대시보드 | 해당 없음 | **대체 불필요** — 사내 대시보드가 IBM보다 사내 데이터 연동 우위 |
| UAK 인증 (gateway-backend) | 해당 없음 | **유지** — 사내 인증 체계 특화 |
| BYOK 키 관리 (aigateway) | 해당 없음 | **유지** — IBM BYOK 미지원 확인됨 |

### 3-4. 배포 형태

- DataPower Gateway를 **Kubernetes Operator**로 온프레미스 클러스터에 배포.
- 기존 DataPower 인프라가 없어도 Kubernetes가 있으면 Operator로 설치 가능 — 별도 하드웨어 불필요.
- DataPower Gateway 하나로 DataPower Interact Gateway + API Connect AI Gateway **두 제품을 동시 활성화** 가능 (각각 라이선스 추가).
- API Connect AI Gateway는 온프레미스 기준 **10.0.8.1 이상** 필요.

### 3-5. 도입 순서

| 단계 | 내용 | 비고 |
|---|---|---|
| **1단계** | DataPower Gateway on Kubernetes 설치 | 공통 기반, 한 번 설치로 두 제품 확보 |
| **2단계** | DataPower Interact Gateway 활성화 | MCP 노출 엔터프라이즈급 구성 |
| **3단계** | API Connect AI Gateway 활성화 | RateLimitFilter 대체, 벤더 라우팅 이관 |

### 3-6. 신경 써야 할 점

| 항목 | 내용 | 대응 |
|---|---|---|
| **DataPower Interact Gateway 성숙도** | IBM Think 2026 갓 출시 — 프로덕션 레퍼런스 사례 부족 | IBM 기술 지원 계약 선행, 안정화 사례 확보 후 확장 |
| **라이선스 비용** | DataPower Gateway + DataPower Interact Gateway + API Connect 각각 유료 | IBM 영업팀 Kubernetes 기반 번들 견적 요청 |
| **BYOK 미지원** ⚠️ | 사용자별 벤더 API 키 관리는 IBM으로 이관 불가 — aigateway 영구 잔류 | 라우팅만 IBM 이관, 키 관리는 aigateway 잔류 구조로 설계 |
| **온프레미스 버전 제약** ⚠️ | API Connect AI Gateway는 온프레미스 10.0.8.1+ 필요 | 인프라팀과 버전 요건 사전 확인 |
| **API Connect AI Gateway 분석 대시보드 중복** | admin에 이미 사내 대시보드 존재 | IBM 대시보드 기능은 비활성화 또는 내부용으로만 사용 |

---

## 4. 트랙 B: watsonx Orchestrate 기반

> **출처**: [IBM watsonx Orchestrate 제품 페이지](https://www.ibm.com/products/watsonx-orchestrate) · [watsonx Orchestrate Documentation](https://www.ibm.com/docs/en/watsonx/watson-orchestrate) · [Model gateway (preview) - IBM Documentation](https://www.ibm.com/docs/en/watsonx/saas?topic=models-model-gateway-preview) · [IBM watsonx.ai 제품 페이지](https://www.ibm.com/products/watsonx-ai)

### 4-1. watsonx Orchestrate 핵심 기능

- **AI 에이전트 생성·실행**: 비즈니스 프로세스를 자동화하는 AI 에이전트를 직접 빌드·배포.
- **멀티에이전트 오케스트레이션**: 여러 에이전트가 작업을 위임·협력하는 A2A 흐름 구성.
- **툴 통합**: 150개 이상의 엔터프라이즈 시스템 커넥터(SAP, Salesforce, ServiceNow 등)를 에이전트 툴로 등록해 자동 호출. (IBM Think 2026 GA 발표 기준)
- **LLM 연동**: 다양한 LLM을 에이전트의 추론 엔진으로 사용.
- **3가지 배포 모델**: IBM Cloud SaaS / AWS SaaS / 온프레미스(Red Hat OpenShift + IBM Software Hub) 중 선택.

> DataPower Interact Gateway와의 차이: watsonx Orchestrate는 **에이전트를 만들고 실행**하는 플랫폼이고, DataPower Interact Gateway는 그 에이전트 트래픽을 **감시·통제**하는 거버넌스 레이어다. 역할이 다르므로 함께 사용될 수도 있다.

### 4-2. watsonx Model Gateway 핵심 기능

- **OpenAI 호환 단일 엔드포인트**: OpenAI·Anthropic·Gemini·IBM Granite 등 복수 벤더로 라우팅.
- **로드밸런싱**: 가용한 모델 전반에 요청을 분산해 성능 최적화. (IBM 공식 문서 확인 — "자동 폴백" 명칭의 별도 기능은 미확인)
- **접근 정책 및 인증 관리**: Secrets Manager 연동을 통한 모델 접근 제어. (토큰 사용량 집계 기능은 공식 문서에서 미확인)

> **현재 상태**: watsonx.ai 플랫폼 자체는 GA이나, **모델 게이트웨이(라우팅) 기능만 프리뷰** — 특정 리전 한정, SaaS 전용.

### 4-3. 사내 기능 대체 범위

| 기능 | 담당 제품 | 대체 판단 |
|---|---|---|
| A2A 에이전트 오케스트레이션 (신규) | watsonx Orchestrate | **신규 도입** — 현 시스템에 없는 기능, 멀티에이전트 전환 시 필요 |
| 벤더별 LLM 라우팅 (aigateway) | aigateway 유지 | **유지** — Model Gateway는 SaaS 전용·프리뷰로 도입 불가. Orchestrate가 호출하는 LLM은 기존 aigateway가 라우팅 |
| MCP 노출 (신규) | 해당 없음 | **비해당** — watsonx Orchestrate는 MCP 게이트웨이가 아님 |
| PromptGuardFilter | 해당 없음 | **대체 불가** — 현 구조 유지 |
| admin-front 대시보드 | 해당 없음 | **대체 불필요** |
| UAK 인증 | 해당 없음 | **유지** |

### 4-4. 배포 형태 및 제약

#### watsonx Orchestrate 배포 모델

IBM 공식 제품 페이지 기준: *"Hybrid, so you can run AI where it makes the most sense—across cloud and on premises"*

| 배포 모델 | 인프라 | 특징 |
|---|---|---|
| **IBM Cloud SaaS** | IBM 관리형 클라우드 | 별도 인프라 없이 즉시 사용 |
| **AWS SaaS** | Amazon Web Services 위 IBM 관리형 | AWS 환경 선호 고객용 |
| **온프레미스** | Red Hat OpenShift + IBM Software Hub | 에어갭(air-gapped) 환경 포함 지원 |

#### 하이브리드 실행 개념

watsonx Orchestrate의 하이브리드는 **데이터·앱은 온프레미스에 유지, 오케스트레이션 레이어(에이전트·정책·거버넌스)는 클라우드 또는 온프레미스 중 선택** 방식이다. 기존 인프라를 교체하지 않고 레이어를 추가하는 방식은 DataPower Interact Gateway의 설계 철학과 동일하다.

| 계층 | 위치 | 설명 |
|---|---|---|
| 데이터·레거시 앱 | **온프레미스 유지** | 기존 시스템 변경 없음 |
| 오케스트레이션 레이어 | 클라우드 또는 온프레미스 **선택** | 에이전트 생성·실행·정책 적용 |
| 커넥터 / API | 양방향 | 150개 이상의 엔터프라이즈 시스템 커넥터로 온프레미스 시스템 연결 |

> **활용 예**: IBM i(AS/400) 워크로드처럼 온프레미스에서만 실행해야 하는 업무는 그대로 두고, Orchestrate가 해당 시스템을 커넥터로 호출하는 방식으로 오케스트레이션만 중앙에서 관리할 수 있다.

#### watsonx Model Gateway (현 시점 미도입)

SaaS 전용·프리뷰 단계로 온프레미스 미지원 — **현 시점 도입 대상 아님**. Orchestrate 단독 도입 구성에서 LLM 라우팅은 기존 aigateway가 담당하며, Model Gateway는 장기적으로 GA 및 온프레미스 지원 여부를 확인 후 재검토한다.

### 4-5. 신경 써야 할 점

| 항목 | 내용 | 대응 |
|---|---|---|
| **온프레미스 인프라 요건** ⚠️ | 온프레미스 배포 시 **Red Hat OpenShift + IBM Software Hub** 필요 — 현재 환경에 OpenShift 없으면 선행 구축 비용 발생 | 인프라팀과 OpenShift 도입 가능 여부 사전 확인 |
| **watsonx Orchestrate 온프레미스 가능** ✅ | 하이브리드 배포 공식 지원, 에어갭 환경 포함 — 온프레미스에서 Orchestrate 기능 사용 가능 | 온프레미스 배포 요건(하드웨어·IBM Software Hub 라이선스) IBM 영업팀 견적 요청 |
| **LLM 라우팅은 aigateway 유지** | Orchestrate가 LLM을 호출할 때 기존 aigateway를 경유 — Orchestrate↔aigateway 연동 구성 설계 필요 | Orchestrate의 LLM 엔드포인트를 aigateway 주소로 설정 |
| **watsonx Model Gateway 미도입** | SaaS 전용·프리뷰 단계로 현 시점 도입 대상 아님 — LLM 라우팅 교체 계획 없음 | Model Gateway GA 및 온프레미스 지원 일정은 장기 모니터링 |
| **watsonx 플랫폼 락인** | watsonx 구독 의존 | 추상화 레이어 유지해 교체 비용 최소화 |

---

## 5. 트랙 비교 및 선택 기준

### 5-1. 트랙별 포지셔닝

| 구분 | 트랙 A (DataPower) | 트랙 B (watsonx Orchestrate) |
|---|---|---|
| **주요 역할** | MCP 트래픽 거버넌스, LLM API 정책 집행 (A2A 향후 예정) | 에이전트 생성·실행·오케스트레이션 (LLM 라우팅은 기존 aigateway 유지) |
| **인프라 요건** | 온프레미스 Kubernetes + DataPower Operator | 클라우드 SaaS 또는 온프레미스(Red Hat OpenShift + IBM Software Hub) |
| **온프레미스 지원** | ✅ 가능 | ✅ Orchestrate 가능 (OpenShift 필요) / ❌ Model Gateway 불가 |
| **MCP 게이트웨이** | ✅ DataPower Interact Gateway | ❌ 해당 없음 |
| **에이전트 실행** | ❌ 해당 없음 (거버넌스만) | ✅ watsonx Orchestrate |
| **LLM 라우팅** | ✅ API Connect AI Gateway (BYOK 제외) | 🔶 watsonx Model Gateway (프리뷰·SaaS) |

### 5-2. 현재 사내 환경(온프레미스 Kubernetes) 기준 선택

| 제품 | 도입 가능 여부 | 비고 |
|---|---|---|
| DataPower Interact Gateway | ✅ 가능 | Kubernetes Operator, 라이선스 필요 |
| API Connect AI Gateway | ✅ 가능 | 동일 DataPower 인프라, 라이선스 필요 |
| mcp-context-forge | ✅ 가능 | 인프라 불필요, 무료 — 경량 대안 |
| watsonx Orchestrate | ✅ 가능 | 하이브리드 배포 공식 지원 (클라우드 + 온프레미스) |
| watsonx Model Gateway | ❌ 불가 | 모델 게이트웨이 기능 SaaS 전용·프리뷰 |

**현 온프레미스 환경 기준 실질적 선택지:**
- **트랙 A**: DataPower Interact Gateway / API Connect AI Gateway (Kubernetes Operator, 유료 라이선스)
- **트랙 A 경량 대안**: mcp-context-forge (무료, 인프라 불필요)
- **트랙 B**: watsonx Orchestrate + 기존 aigateway 병행 — Orchestrate가 에이전트 오케스트레이션을 담당하고, LLM 라우팅은 현 aigateway가 그대로 유지. Model Gateway는 현 시점 도입 대상 아님.

### 5-3. MCP 노출 기준 비교 (현 시점 핵심 결정)

| 선택지 | 비용 | 운영 부담 | 거버넌스 수준 | 리스크 |
|---|---|---|---|---|
| **mcp-context-forge** | 무료 | 자체 운영 | 기본 (IBM 지원 없음) | 낮음 |
| **DataPower Interact Gateway** | 유료 (라이선스) | DataPower Operator 운영 | 엔터프라이즈급 | 중 (갓 출시 성숙도) |

---

## 6. 트랙 무관하게 유지되는 것

어떤 트랙을 선택하든 아래 항목은 IBM으로 대체하지 않고 현 구조를 유지한다.

| 항목 | 이유 |
|---|---|
| **PromptGuardFilter** | 외부 ML 서비스(KR_PATTERN + ML 모델) + ES 보안 로그 연동 구조 — IBM이 동등하게 재현 불가 |
| **BYOK 키 관리 (aigateway)** | IBM API Connect·DataPower 모두 사용자별 벤더 키 개별 관리 미지원 |
| **admin-front 대시보드** | 부서별·모델별 비용·보안 탐지 통계 등 이미 IBM보다 넓은 범위를 사내 데이터와 연동해 제공 |
| **UAK 인증 (gateway-backend)** | 사내 인증 체계에 특화, IBM으로 대체하면 손해 |

---

## 부록: 참고 출처

### 트랙 A — DataPower 기반
- [IBM DataPower Gateway 제품 페이지](https://www.ibm.com/products/datapower-gateway)
- [DataPower Kubernetes Operator - IBM Documentation](https://www.ibm.com/docs/en/datapower-operator)
- [Announcing IBM DataPower Interact Gateway](https://www.ibm.com/new/announcements/datapower-interact-gateway-for-governed-ai-interactions)
- [AI Gateway - IBM API Connect](https://www.ibm.com/products/api-connect/ai-gateway)
- [Using the AI Gateway - IBM Documentation](https://www.ibm.com/docs/en/api-connect/cloud/10.0.x_saas?topic=definitions-using-ai-gateway-support-apis-ai-applications)
- [API Connect AI Gateway 모델 확장 발표](https://www.ibm.com/new/announcements/ibm-api-connect-expands-its-ai-gateway-feature-to-additional-models-and-deployments)
- [API Connect AI Gateway - Develop and Test an API](https://community.ibm.com/community/user/blogs/jean-paul-tabja/2025/05/14/api-connect-ai-gateway-develop-and-test-an-api)
- [IBM API Connect on Kubernetes](https://www.ibm.com/docs/en/api-connect/10.0.x?topic=installing-api-connect-kubernetes)
- [DataPower GatewayScript API - IBM Documentation](https://www.ibm.com/docs/en/datapower-gateway/10.6?topic=programming-gatewayscript-api)

### 트랙 B — watsonx Orchestrate 기반
- [IBM watsonx Orchestrate 제품 페이지](https://www.ibm.com/products/watsonx-orchestrate) — 하이브리드(클라우드+온프레미스) 지원 명시: *"Hybrid, so you can run AI where it makes the most sense—across cloud and on premises"*
- [watsonx Orchestrate Documentation](https://www.ibm.com/docs/en/watsonx/watson-orchestrate)
- [IBM watsonx.ai 제품 페이지](https://www.ibm.com/products/watsonx-ai)
- [Model gateway (preview) - IBM Documentation](https://www.ibm.com/docs/en/watsonx/saas?topic=models-model-gateway-preview)
- [IBM watsonx Orchestrate on-premises — IBM Software Hub](https://www.ibm.com/docs/en/software-hub) — Red Hat OpenShift 기반 온프레미스 배포 기술 기반
- [IBM watsonx Orchestrate features](https://www.ibm.com/products/watsonx-orchestrate#features) — 에어갭 환경 지원 명시 (커넥터 수: IBM Think 2026 GA 발표 기준 150개 이상)

### 공통
- [What Is An AI Gateway? | IBM](https://www.ibm.com/think/topics/ai-gateway)
- [GitHub - IBM/mcp-context-forge](https://github.com/IBM/mcp-context-forge)
