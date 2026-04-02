# Handoff - 2026-04-02 Master Integration

## 1. 목적

이 문서는 2026-04-02 기준으로 현재까지 구현/통합된 범위와 다음 세션에서 이어서 개발해야 할 범위를 정리한 handoff 문서다.

- 기준 브랜치: `master`
- 기준 커밋: `baff4403eba94b0a2332cb1839d8bbd9bd0676d2`
- 검증 기준: `./gradlew test`

다음 세션에서는 이 문서를 시작점으로 삼아 `master`에서 새 기능 브랜치를 다시 만들어 진행하면 된다.

## 2. 이번에 master에 통합된 브랜치

### 2.1 반영된 기능 브랜치

1. `feature/operating-mode-guard`
   - 커밋: `0908213`
   - 내용:
     - 운영 모드 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE` 도입
     - 실행 트리거 `MANUAL`, `AUTOMATED` 도입
     - 실행 차단 사유 코드화
     - 수동 실행과 자동 실행의 허용 규칙 분리
     - 대시보드에 실행 가드 스냅샷 노출

2. `feature/pricing-retry-policy`
   - 커밋: `48c9ade`
   - 내용:
     - 최근 체결가 중심 구조를 `bestBid` / `bestAsk` / `lastPrice` 기반 구조로 확장
     - 매수는 최우선 매도호가, 매도는 최우선 매수호가 기준으로 주문가 계산
     - 퍼센트 버퍼 대신 tick 기반 가격 정책 도입
     - 재시도 시마다 최신 호가를 다시 조회해 주문가 재산정
     - 재시도 횟수/대기시간을 설정으로 외부화

3. `feature/security-private-surface`
   - 커밋: `089ea90`
   - 내용:
     - 대시보드/Swagger/Actuator 공개 경로 하드코딩 제거
     - `trading.security.public-path-prefixes` 설정 기반으로 공개 범위 제어
     - 기본값은 비공개

### 2.2 master 병합 커밋

- `926e622`: `feature/pricing-retry-policy` 병합
- `baff440`: `feature/security-private-surface` 병합

`feature/pricing-retry-policy` 안에 `feature/operating-mode-guard` 변경이 포함되어 있으므로 운영 모드 가드도 함께 master에 들어가 있다.

## 3. 현재 코드 기준으로 반영된 핵심 변경

### 3.1 운영 모드 / 실행 가드

- `ExecutionGuard`가 운영 모드와 실행 트리거를 함께 판단한다.
- `PAPER`에서는 실주문이 항상 차단된다.
- `MANUAL_LIVE`에서는 수동 실행만 허용되고 자동 실행은 차단된다.
- `AUTO_LIVE`에서도 `trading.execution.enabled=false` 또는 Kill Switch 활성화 시 차단된다.
- 수동 리밸런싱은 실행이 차단되더라도 job을 planned 상태로 만들 수 있게 정리되어 있다.

관련 주요 파일:

- `src/main/java/my/side/trading/core/application/execution/ExecutionGuard.java`
- `src/main/java/my/side/trading/core/application/orchestration/RebalanceOrchestrator.java`
- `src/main/java/my/side/trading/adapter/in/scheduler/RebalanceExecutionScheduler.java`
- `src/main/java/my/side/trading/adapter/in/web/execution/ExecutionJobController.java`

### 3.2 주문 가격 정책 / 재시도 정책

- `RealtimePriceProvider`가 `RealtimeQuote`를 반환할 수 있게 확장됐다.
- `KisRealtimeMessageHandler`가 best bid / ask를 메모리 시세 저장소에 반영한다.
- `ExecutionOrderFactory`가 주문 방향별 호가 우선 정책으로 주문가를 만든다.
- `RetryableOrderExecutor`가 재시도마다 현재 호가를 다시 조회해 새 limit price를 계산한다.
- 가격 정책은 `trading.pricing.*` 설정으로 관리한다.

관련 주요 파일:

- `src/main/java/my/side/trading/core/domain/portfolio/RealtimeQuote.java`
- `src/main/java/my/side/trading/core/domain/portfolio/RealtimePriceProvider.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionOrderFactory.java`
- `src/main/java/my/side/trading/core/application/execution/RetryableOrderExecutor.java`
- `src/main/java/my/side/trading/core/application/execution/pricing/MarketLikePricingPolicy.java`
- `src/main/java/my/side/trading/core/infrastructure/config/TradingPricingProps.java`

### 3.3 보안 공개 범위

- `ApiKeyAuthFilter`, `RateLimitFilter`, `WebFilterConfig`가 공개 경로를 설정으로 판별한다.
- 기본값은 모두 보호 대상이다.
- 운영/개발 환경에서 공개가 필요한 경우에만 `trading.security.public-path-prefixes`에 추가한다.

관련 주요 파일:

- `src/main/java/my/side/trading/core/adapter/in/web/security/ApiKeyAuthFilter.java`
- `src/main/java/my/side/trading/core/adapter/in/web/security/RateLimitFilter.java`
- `src/main/java/my/side/trading/core/adapter/in/web/security/WebFilterConfig.java`
- `src/main/java/my/side/trading/core/infrastructure/config/TradingSecurityProps.java`
- `src/main/resources/application.yml`
- `src/main/resources/application-prod.yml`

## 4. 검증 결과

- 실행한 검증: `./gradlew test`
- 결과: 성공
- 확인 시각: 2026-04-02 KST

참고:

- 테스트 로그에서 Mockito agent 경고가 보인다.
- `TradingApplicationTests`는 로컬 MySQL에 연결한다.
- 현재는 통과하지만, 이후 세션에서 테스트 격리를 강화할 여지가 있다.

## 5. 아직 남아 있는 우선 개발 항목

현재 계획 기준으로 다음 3개가 우선순위 상 남아 있다.

### 5.1 1순위: `feature/market-calendar-policy`

목표:

- 고정 KST 시각 의존을 줄이고 시장 상태 기반으로 자동 실행 여부를 판단

해야 할 일:

- `MarketStatus` 도입: `REGULAR`, `HOLIDAY`, `EARLY_CLOSE`, `DATA_UNCERTAIN`
- 시장 상태 판단 포트/정책 추가
- `RebalanceExecutionScheduler` 자동 실행 전에 시장 상태 확인
- `StrategyEodScheduler`도 EOD skip 조건 또는 데이터 미확정 조건 반영
- 운영 모드와 연결:
  - `HOLIDAY`: 자동 실행 skip
  - `EARLY_CLOSE`: 자동 실행 skip 또는 `MANUAL_LIVE` 유도
  - `DATA_UNCERTAIN`: 자동 실행 금지

시작 전에 볼 문서:

- `docs/OPERATIONS_RUNBOOK.md`
- `docs/CURRENT_IMPLEMENTATION_SYNC.md`

권장 시작 브랜치:

- `git switch -c feature/market-calendar-policy`

### 5.2 2순위: `feature/circuit-breaker-stateful`

목표:

- VIX Circuit Breaker가 실제 이전 비중을 반영해 동작하도록 보강

해야 할 일:

- `RebalanceDecisionService`에서 `prevWeights`를 실제 전달하도록 경로 보강
- 또는 `CircuitBreakerService`가 null 없이 판단할 수 있는 정책으로 재설계
- 200MA와 VIX 동시 적용 시나리오 테스트 강화

현재 리스크:

- 문서상 VIX 방어 규칙이 완전히 살아 있지 않다.
- 로그는 남지만 실제 비중 제한은 약할 수 있다.

권장 시작 브랜치:

- `git switch -c feature/circuit-breaker-stateful`

단, 이 작업은 `market-calendar-policy` 이후에 가는 편이 운영 가드 구조상 자연스럽다.

### 5.3 3순위: `feature/ops-kpi-risk-controls`

목표:

- KPI, 실행 리스크 한도, 최소 장애 알림의 최소 운영 모델 추가

해야 할 일:

- 대시보드/조회 모델에 운영 KPI 추가
- EOD 성공률, 중복 Job, 미정리 주문, 주문 실패율 집계 모델 추가
- 리스크 한도 정책 타입 정의
- KPI breach 시 `AUTO_LIVE` 중지 또는 모드 강등 연결
- 최소 장애 알림 이벤트 골격 정의

권장 시작 브랜치:

- `git switch -c feature/ops-kpi-risk-controls`

## 6. 다음 세션에서 권장하는 진행 순서

1. `master` 최신 상태 확인
2. `feature/market-calendar-policy` 생성
3. 시장 상태 정책 + 스케줄 진입 가드 구현
4. `./gradlew test`
5. 커밋
6. 그 다음 `feature/circuit-breaker-stateful`
7. 마지막으로 `feature/ops-kpi-risk-controls`

## 7. 다음 세션용 체크 포인트

- `master` 기준점은 `baff440`이다.
- 워크트리는 이 handoff 문서까지 포함해 정리한 뒤 커밋해야 한다.
- `application.yml`에는 현재 아래 설정이 함께 들어가 있다.
  - `trading.operation.*`
  - `trading.pricing.*`
  - `trading.security.*`
- 다음 브랜치 작업 시 `application.yml` 충돌 가능성이 높으므로 설정 추가는 블록 단위로 조심해서 넣어야 한다.
- 보안 관련 기본 정책은 “비공개 기본값”이다. 새 API를 추가할 때 공개 예외를 하드코딩하지 않는다.
- 자동 실행 관련 기능은 `master`에 이미 운영 모드/트리거 개념이 있으므로 이 구조를 재사용해야 한다.

## 8. 요약

현재 `master`에는 아래 3개가 통합 완료됐다.

- 운영 모드 기반 실행 가드
- 설정 기반 보안 공개 범위
- 호가 기반 주문가 및 재시도 재산정

다음 세션의 첫 작업은 `feature/market-calendar-policy`를 `master`에서 새로 만들어 시장 상태 기반 실행 제어를 붙이는 것이다.
