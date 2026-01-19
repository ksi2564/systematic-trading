# QQQ 자동매매 시스템 - 현재 구현 현황서

> 작성일: 2026-01-14  
> 버전: 1.0

---

## 📋 개요

본 문서는 QQQ 자동매매 시스템의 현재 구현 상태를 정리한 기술 참고 문서입니다.

---

## 🏗️ 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Adapter Layer (In)                          │
├─────────────────────────────────────────────────────────────────────┤
│  StrategyEodScheduler          │  (웹 컨트롤러 - 미구현)            │
│  - 06:15 KST EOD 실행          │                                    │
│  - VIX/200MA 조회              │                                    │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Application Layer                            │
├─────────────────────────────────────────────────────────────────────┤
│  RebalanceOrchestrator         │  StrategyStateEodService           │
│  - 자동매매 시작점             │  - ATH/DD 계산                     │
│  - Circuit Breaker 연동        │  - StrategyState 저장              │
├────────────────────────────────┼────────────────────────────────────┤
│  RebalanceDecisionService      │  CircuitBreakerService             │
│  - 리밸런싱 판단               │  - VIX >= 35 필터                  │
│  - 목표 비중 vs 실제 비중      │  - QQQ < 200MA 필터                │
├────────────────────────────────┼────────────────────────────────────┤
│  ExecutionJobCreateService     │  ExecutionJobExecutor              │
│  - 주문 Job 생성               │  - 주문 실행                       │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          Domain Layer                               │
├─────────────────────────────────────────────────────────────────────┤
│  StrategyState     │  WeightSet        │  Portfolio                 │
│  DdBucket          │  StrategyPhase    │  Position                  │
│  ExecutionJob      │  ExecutionOrder   │  RebalanceDecision         │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Adapter Layer (Out)                           │
├─────────────────────────────────────────────────────────────────────┤
│  KIS API                       │  Yahoo Finance API                 │
│  - KisAuthService              │  - YahooVixService                 │
│  - KisOverseasQuotedPriceService│ - VIX 현재가                      │
│  - KisOverseasBalanceService   │  - QQQ 200MA                       │
│  - KisOverseasOrderService     │                                    │
├────────────────────────────────┴────────────────────────────────────┤
│  JPA Repositories              │  실시간 WebSocket (부분 구현)      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🔧 구현 완료 기능

### 1. 전략 상태 관리 (StrategyState)

| 필드 | 타입 | 설명 |
|------|------|------|
| `asOfDate` | LocalDate | 상태 기준 일자 (EOD) |
| `ath` | BigDecimal | QQQ 전고점 (All-Time High) |
| `lastClose` | BigDecimal | QQQ 전일 종가 |
| `drawdownPct` | BigDecimal | 현재 DD (0~100) |
| `maxDrawdownPctSinceAth` | BigDecimal | ATH 이후 최대 DD |
| `ddBucket` | DdBucket | DD 구간 (LESS_THAN_15, FROM_15_TO_25, ...) |
| `phase` | StrategyPhase | 전략 단계 (NORMAL, DRAWDOWN, RECOVERY) |
| `targetWeights` | WeightSet | 목표 비중 (QQQ/QLD/TQQQ) |
| `strategyOn` | boolean | 전략 활성화 여부 (KillSwitch) |
| `version` | int | 전략 버전 |

**DD 구간별 목표 비중:**

| DdBucket | DD 범위 | QQQ | QLD | TQQQ |
|----------|---------|-----|-----|------|
| LESS_THAN_15 | 0% ~ 15% | 100 | 0 | 0 |
| FROM_15_TO_25 | 15% ~ 25% | 60 | 30 | 10 |
| FROM_25_TO_35 | 25% ~ 35% | 40 | 40 | 20 |
| FROM_35_TO_45 | 35% ~ 45% | 30 | 30 | 40 |
| MORE_THAN_45 | 45%+ | 20 | 20 | 60 |

---

### 2. Circuit Breaker (안전장치) 🆕

**구현 파일:**
- `TradingCircuitBreakerProps.java` - 설정 클래스
- `CircuitBreakerService.java` - 필터 로직
- `YahooVixService.java` - 외부 API 연동

**필터 로직:**

```java
// VIX 필터: VIX >= 35 시 TQQQ 확대 중단
public boolean isVixTriggered(BigDecimal vix) {
    return vix.compareTo(props.getVixThreshold()) >= 0;
}

// 200MA 필터: QQQ < 200MA 시 TQQQ 한 단계 보수적
public boolean isMaTriggered(BigDecimal qqqClose, BigDecimal ma200) {
    return qqqClose.compareTo(ma200) < 0;
}
```

**비중 조정 로직:**

| 조건 | 동작 |
|------|------|
| 200MA 트리거 | TQQQ 비중 한 단계 하향 (60→40→20→10→0) |
| VIX 트리거 | 이전 비중 대비 TQQQ 증가 금지 |
| 둘 다 트리거 | 200MA 먼저 적용 후 VIX 체크 |

**설정 (application.yml):**

```yaml
trading:
  circuit-breaker:
    enabled: true        # 전체 on/off
    vix-enabled: true    # VIX 필터 on/off
    vix-threshold: 35    # VIX 임계값
    ma-period: 200       # 이동평균 기간
```

---

### 3. 리밸런싱 판단 (RebalanceDecisionService)

**판단 로직:**

```java
public RebalanceDecision decide(
    StrategyState state, 
    Portfolio portfolio, 
    BigDecimal vix,       // Circuit Breaker용
    BigDecimal qqqMa200   // Circuit Breaker용
) {
    // 1. 전략 OFF면 리밸런싱 안함
    if (!state.strategyOn()) return no("strategyOn=false");
    
    // 2. 총자산 0 이하면 안함
    if (total <= 0) return no("총자산이 0 이하");
    
    // 3. Circuit Breaker 적용
    WeightSet target = circuitBreakerService.adjustWeights(
        originalTarget, prevWeights, vixTriggered, maTriggered);
    
    // 4. 비중 편차 체크 (±5% 이상 이탈 시 리밸런싱)
    if (!exceeds) return no("허용범위 이내");
    
    // 5. 주문 인텐트 생성 (SELL 먼저, BUY 나중)
    return yes(RebalanceType.THRESHOLD, reason, intents);
}
```

**주문 우선순위:**
- 매도: TQQQ → QLD → QQQ (레버리지 높은 순)
- 매수: QQQ → QLD → TQQQ (레버리지 낮은 순)

---

### 4. Yahoo Finance API 연동 🆕

**VIX 조회:**
```
GET https://query1.finance.yahoo.com/v8/finance/chart/^VIX
```

**QQQ 200일 종가:**
```
GET https://query1.finance.yahoo.com/v8/finance/chart/QQQ?range=1y&interval=1d
```

**응답 파싱:**
```java
public record YahooQuoteResponse(Chart chart) {
    public BigDecimal getCurrentPrice();      // 현재가
    public List<BigDecimal> getClosePrices(); // 과거 종가 리스트
}
```

---

### 5. 한국투자증권 API 연동

| 서비스 | API | 용도 |
|--------|-----|------|
| `KisAuthService` | `/oauth2/tokenP` | 접근토큰 발급 |
| `KisOverseasQuotedPriceService` | `/quotations/price` | 해외주식 현재가 |
| `KisOverseasBalanceService` | `/acct/balance` | 계좌 잔고 조회 |
| `KisOverseasOrderService` | `/order` | 해외주식 주문 |

---

### 6. 스케줄러

**StrategyEodScheduler:**
- 실행 시간: 매일 06:15 KST (화~토)
- 동작:
  1. QQQ 전일 종가 조회 (KIS API)
  2. VIX/200MA 조회 (Yahoo Finance)
  3. StrategyState 계산 및 저장

**조건부 활성화:**
```yaml
trading:
  scheduling:
    enabled: false  # true로 변경 시 활성화
```

---

### 7. 주문 실행 (ExecutionJob)

**주문 상태 흐름:**
```
QUEUED → PENDING → SUBMITTED → FILLED/PARTIALLY_FILLED/FAILED
```

**ExecutionJobExecutor:**
- 장중 실행 (장 시작 후 15~30분)
- 시장가 또는 제한적 지정가 (±0.5%)
- 실패 시 재시도

---

## 📁 파일 구조

```
src/main/java/my/side/trading/
├── adapter/
│   ├── in/
│   │   └── scheduler/
│   │       └── StrategyEodScheduler.java
│   └── out/
│       ├── kis/
│       │   ├── client/
│       │   │   ├── KisAuthService.java
│       │   │   ├── KisOverseasBalanceService.java
│       │   │   ├── KisOverseasOrderService.java
│       │   │   └── KisOverseasQuotedPriceService.java
│       │   ├── config/
│       │   │   └── KisProps.java
│       │   └── dto/
│       │       └── (각종 Request/Response DTO)
│       ├── yahoo/                            # 🆕 신규
│       │   ├── YahooVixService.java
│       │   └── dto/
│       │       └── YahooQuoteResponse.java
│       └── persistence/
│           └── (JPA Repository 구현체)
├── core/
│   ├── application/
│   │   ├── execution/
│   │   │   ├── ExecutionJobCreateService.java
│   │   │   ├── ExecutionJobExecutor.java
│   │   │   └── RebalanceDecisionService.java  # 🔄 수정됨
│   │   ├── orchestration/
│   │   │   └── RebalanceOrchestrator.java     # 🔄 수정됨
│   │   ├── portfolio/
│   │   │   └── PortfolioService.java
│   │   └── strategy/
│   │       ├── CircuitBreakerService.java     # 🆕 신규
│   │       └── StrategyStateEodService.java
│   ├── domain/
│   │   ├── execution/
│   │   │   ├── job/
│   │   │   ├── order/
│   │   │   └── plan/
│   │   ├── portfolio/
│   │   │   ├── Portfolio.java
│   │   │   ├── Position.java
│   │   │   └── Ticker.java
│   │   └── strategy/
│   │       ├── DdBucket.java
│   │       ├── StrategyPhase.java
│   │       ├── StrategyState.java
│   │       └── WeightSet.java
│   └── infrastructure/
│       └── config/
│           ├── TradingCircuitBreakerProps.java  # 🆕 신규
│           └── TradingStrategyProps.java
└── TradingApplication.java
```

---

## 🧪 테스트 현황

| 테스트 클래스 | 테스트 수 | 상태 |
|--------------|----------|------|
| `CircuitBreakerServiceTest` | 10 | ✅ 통과 |
| `RebalanceDecisionServiceTest` | 3 | ✅ 통과 |
| `WeightSetTest` | - | ✅ 통과 |
| `DdBucketTest` | - | ✅ 통과 |
| `ExecutionJobExecutorTest` | - | ✅ 통과 |

---

## 🔮 향후 구현 필요 항목

### 우선순위 높음
- [ ] 실시간 체결/잔고 동기화 (5~10분 주기)
- [ ] EOD 스냅샷 저장 (NAV, 비중, 주문 이력)
- [ ] Slack/Email 알림

### 우선순위 중간
- [ ] 웹 대시보드 (전략 관리, 성과 시각화)
- [ ] 공개용 Read-only 성과 페이지

### 우선순위 낮음
- [ ] 백테스트 시뮬레이터
- [ ] AI 기반 Insight
- [ ] 전략 버전 관리

---

## 📝 의사결정 기록

| 날짜 | 결정 | 사유 |
|------|------|------|
| 2026-01-14 | VIX 조회에 Yahoo Finance 사용 | KIS API에서 VIX 직접 조회 불가 |
| 2026-01-14 | 200MA도 Yahoo Finance 사용 | 1년치 데이터 한 번에 조회 가능 (KIS는 100건 제한) |
| 2026-01-14 | Circuit Breaker 설정으로 on/off 가능 | 운영 유연성 확보 |
