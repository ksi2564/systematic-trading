# QQQ 자동매매 시스템 - 시스템 설계 및 전략 명세서

---

## 1. 프로젝트 개요

### 1.1. 목표
본 프로젝트는 **한국투자증권(KIS) Open API**를 활용하여 QQQ, QLD, TQQQ ETF로 구성된 포트폴리오를 시장의 고점 대비 하락률(Drawdown, DD)에 따라 자동으로 관리하는 시스템을 구현하는 것을 목표로 합니다. 실시간 시세 수집, 주문 집행, 계좌 관리를 모두 자동화합니다.

### 1.2. 핵심 철학
- **동적 자산 배분**: 시장 변동성(ATH 대비 하락률)에 따라 레버리지 노출도(1배, 2배, 3배)를 동적으로 조절합니다.
- **자동화된 실행**: 신호 생성부터 주문 집행, 정산까지의 전 과정을 자동화하여 매매에서 인간의 개입과 감정을 배제합니다.
- **신뢰성 및 감사 가능성**: 모든 의사결정과 주문 집행 내역을 기록하여 추적 및 복구가 가능하도록 설계합니다.

---

## 2. 시스템 아키텍처

본 프로젝트는 비즈니스 로직을 외부 기술로부터 분리하기 위해 **헥사고날 아키텍처(Hexagonal Architecture, Ports and Adapters)**를 채택했습니다.

### 2.1. 기술 스택 (Tech Stack)
- **Language**: Java 21 (LTS)
- **Framework**: Spring Boot 3.5.7
- **Build Tool**: Gradle
- **Database**: MySQL (영속성), Caffeine (로컬 캐시)
- **Communication**: Spring WebFlux (KIS API 연동을 위한 Non-blocking I/O)
- **Scheduling**: Spring Scheduler (Cron 기반 배치 작업)

### 2.2. 패키지 구조
```
src/main/java/my/side/trading
├── core                # 순수 비즈니스 로직
│   ├── domain          # 엔티티, 값 객체 (Strategy, WeightSet, DdBucket)
│   └── application     # 유스케이스, 서비스 오케스트레이션
├── adapter             # 인프라 연동
│   ├── in              # 인바운드 (웹 컨트롤러, 스케줄러)
│   └── out             # 아웃바운드 (KIS API, Yahoo Finance, DB)
└── shared              # 공통 유틸리티
```

---

## 3. 트레이딩 전략 (Feature 1)

핵심 알고리즘은 QQQ의 전고점(ATH) 대비 하락률(DD)에 따라 포트폴리오 비중을 조절합니다.

### 3.1. 하락률 구간 정의 (`DdBucket`)
시장 상태를 DD 비율에 따라 5단계로 분류합니다:

| 구간 (Bucket) | DD 범위 | 설명 |
| :--- | :--- | :--- |
| **LESS_THAN_15** | 0% ≤ DD < 15% | 평상시 (Normal) |
| **FROM_15_TO_25** | 15% ≤ DD < 25% | 초기 조정 |
| **FROM_25_TO_35** | 25% ≤ DD < 35% | 약세장 진입 |
| **FROM_35_TO_45** | 35% ≤ DD < 45% | 깊은 하락 |
| **MORE_THAN_45** | DD ≥ 45% | 위기 상황 |

### 3.2. 목표 비중 할당 (`WeightSet`)
각 구간별로 QQQ(1배), QLD(2배), TQQQ(3배)의 목표 비중이 사전 정의되어 있습니다:

| 구간 | QQQ | QLD | TQQQ |
| :--- | :--- | :--- | :--- |
| **LESS_THAN_15** | 100% | 0% | 0% |
| **FROM_15_TO_25** | 60% | 30% | 10% |
| **FROM_25_TO_35** | 40% | 40% | 20% |
| **FROM_35_TO_45** | 30% | 30% | 40% |
| **MORE_THAN_45** | 20% | 20% | 60% |

*참고: 하락 후 회복 시(저점 대비 -10% 이내 도달), "Recovery Mode" (QQQ 70%, QLD 30%)가 발동될 수 있습니다.*

### 3.3. 리밸런싱 로직
- **발동 조건 (Trigger)**:
    1. 정기 월간 리밸런싱.
    2. 임계치 이탈: 현재 비중이 목표 비중과 ±5% 이상 차이 날 경우.
- **실행 순서**:
    - **매도**: TQQQ → QLD → QQQ (리스크 축소 우선).
    - **매수**: QQQ → QLD → TQQQ (안정성 확보 우선).

---

## 4. 실행 엔진 (Feature 3)

애플리케이션 계층(Application Layer)은 실행 흐름을 제어하며 API 장애에 대한 견고성을 보장합니다.

### 4.1. 주문 집행 컴포넌트
- **`RetryableOrderExecutor`**: 일시적인 API 오류에 대비해 지수 백오프(Exponential Backoff)를 적용한 재시도 로직을 수행합니다.
- **`OrderValidator`**: 주문 전제 조건(장 운영 시간, 예수금 확보, 서킷 브레이커 등)을 검증합니다.
- **`ExecutionResult`**: 모든 주문의 결과(체결, 거부, 부분 체결)를 캡처합니다.

### 4.2. 안전 장치 (Circuit Breakers)
- **VIX 필터**: VIX 지수가 35 이상일 경우 리스크 회피(Risk-off) 모드로 전환합니다.
- **MA200 필터**: QQQ가 200일 이동평균선 아래일 경우 보수적인 비중을 유지합니다.

---

## 5. 인터페이스 및 API (Feature 2)

현재 시스템은 REST API 기반으로 관리되도록 설계되었습니다 (**API-First**).

### 5.1. 웹 컨트롤러 (`adapter.in.web`)
- **`ExecutionJobController`**: 배치 작업(장 마감 정산, 계산 등)을 수동으로 트리거합니다.
- **`RebalanceController`**: 리밸런싱 조건을 확인하거나 강제로 리밸런싱을 수행합니다.
- **`KisAuthController`**: KIS API 접근 토큰을 관리(발급/폐기)합니다.
- **`RealtimeQuoteController`**: 실시간 시세를 조회합니다.

### 5.2. 모니터링 (계획)
- 모든 로그는 중앙에서 수집됩니다.
- 향후 대시보드(Web Dashboard)를 통해 다음 정보를 시각화할 예정입니다:
    - 현재 포트폴리오 vs 목표 비중
    - 실시간 손익(PnL)
    - 시스템 상태 (Health Status)

---

## 6. 개발 현황

| 컴포넌트 | 상태 | 비고 |
| :--- | :--- | :--- |
| **Core Domain** | ✅ 완료 | 전략, 비중(WeightSet), DD 로직 구현 완료. |
| **KIS Adapter** | ✅ 완료 | 시세, 주문, 인증 연동 완료. |
| **Scheduler** | ✅ 완료 | 장 마감(EOD) 정산, 시장 감시 스케줄러 설정 완료. |
| **Web API** | 🚧 진행 중 | 기본 컨트롤러 구현됨; 보안/인증 고도화 필요. |
| **Dashboard** | ❌ 대기 | 추후 React/Next.js 별도 프로젝트로 진행 예정. |
