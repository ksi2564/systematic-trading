# Handoff - 2026-04-02 Master Integration

## 1. 목적

이 문서는 2026-04-02 기준으로 `master`에 실제 반영된 구현 범위와 다음 세션에서 이어서 볼 포인트를 정리한 handoff 문서다.

- 기준 브랜치: `master`
- 기준 커밋: `abccf3c`
- 검증 기준: `./gradlew test`

다음 세션에서는 이 문서를 시작점으로 삼아 `master`에서 새 기능 브랜치를 만들어 진행하면 된다.

## 2. 이번에 master에 통합된 브랜치

### 2.1 반영된 기능 브랜치

기존에 이미 반영돼 있던 기능:

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

이번 세션에서 추가 반영한 기능:

4. `feature/market-calendar-policy`
   - 커밋: `fdbebd7`
   - 내용:
     - `MarketStatus` 도입: `REGULAR`, `HOLIDAY`, `EARLY_CLOSE`, `DATA_UNCERTAIN`
     - 설정 기반 시장 캘린더 판별기 추가
     - 자동 리밸런싱 스케줄러에 시장 상태 진입 가드 연결
     - EOD 스케줄러에 휴장/데이터 미확정 skip 반영
     - 시장일자를 뉴욕 기준으로 해석하도록 정리

5. `feature/circuit-breaker-stateful`
   - 커밋: `014eb6c`
   - 내용:
     - `StrategyStateRepository`에 직전 상태 조회 계약 추가
     - `RebalanceOrchestrator`가 직전 전략 상태의 `targetWeights`를 실제 전달
     - VIX 필터가 “이전보다 공격적 확대 금지”를 실제 반영하도록 보강
     - `prevWeights` 부재 시 예외 대신 경고 로그와 안전한 fallback 적용

6. `feature/ops-kpi-risk-controls`
   - 커밋: `a17a9ff`
   - 내용:
     - 운영 KPI 스냅샷 모델 추가
     - 최신 EOD 성공 여부, 중복 Job 정황, 미정리 주문, 주문 실패율 집계
     - 대시보드 요약 응답에 KPI 정보 노출
     - KPI breach 시 자동 실행을 `KPI_BREACH`로 차단하도록 실행 가드 연결

### 2.2 master 병합 커밋

- `926e622`: `feature/pricing-retry-policy` 병합
- `baff440`: `feature/security-private-surface` 병합
- `132acf2`: `feature/market-calendar-policy` 병합
- `2fa2e0a`: `feature/circuit-breaker-stateful` 병합
- `abccf3c`: `feature/ops-kpi-risk-controls` 병합

현재 `master`에는 위 6개 기능이 모두 통합되어 있다.

## 3. 현재 코드 기준 핵심 변경

### 3.1 시장 상태 기반 실행 제어

- 자동 리밸런싱은 `REGULAR`에서만 진입한다.
- EOD 스케줄은 `REGULAR`, `EARLY_CLOSE`에서만 수행한다.
- `HOLIDAY`, `DATA_UNCERTAIN`은 scheduled EOD와 자동 리밸런싱을 차단한다.
- 시장일자는 `America/New_York` 기준으로 계산한다.
- 휴장/조기폐장/데이터 미확정일은 `trading.market-calendar.*` 설정으로 관리한다.

관련 주요 파일:

- `src/main/java/my/side/trading/adapter/in/scheduler/RebalanceExecutionScheduler.java`
- `src/main/java/my/side/trading/adapter/in/scheduler/StrategyEodScheduler.java`
- `src/main/java/my/side/trading/core/application/market/MarketCalendarService.java`
- `src/main/java/my/side/trading/adapter/out/calendar/ConfigDrivenMarketStatusReader.java`
- `src/main/java/my/side/trading/core/infrastructure/config/TradingMarketCalendarProps.java`

### 3.2 상태 기반 Circuit Breaker

- `RebalanceOrchestrator`가 최신 전략 상태 외에 직전 전략 상태도 조회한다.
- `RebalanceDecisionService`는 직전 상태의 `targetWeights`를 `prevWeights`로 전달받는다.
- 200MA 필터를 먼저 적용한 뒤 VIX 필터를 적용한다.
- VIX 트리거 시 조정 결과의 `TQQQ` 비중이 직전 비중보다 커지면 직전 비중으로 제한한다.
- 직전 비중이 없으면 예외를 던지지 않고 경고 로그만 남기고 기존 조정 비중을 사용한다.

관련 주요 파일:

- `src/main/java/my/side/trading/core/domain/strategy/StrategyStateRepository.java`
- `src/main/java/my/side/trading/core/application/orchestration/RebalanceOrchestrator.java`
- `src/main/java/my/side/trading/core/application/execution/RebalanceDecisionService.java`
- `src/main/java/my/side/trading/core/application/strategy/CircuitBreakerService.java`

### 3.3 운영 KPI와 자동 실행 리스크 가드

- 운영 KPI 스냅샷이 대시보드 요약에 포함된다.
- 현재 KPI 집계 범위:
  - 최신 EOD 성공 여부
  - 중복 `signalDate` Job 정황
  - 미정리 주문 수
  - 주문 실패율
- 자동 실행은 KPI breach가 있으면 `ExecutionBlockReason.KPI_BREACH`로 차단된다.
- 수동 실행은 KPI breach만으로 차단되지 않는다.
- KPI 기준은 `trading.operation.kpi.*` 설정으로 관리한다.

관련 주요 파일:

- `src/main/java/my/side/trading/core/application/operation/OperationsKpiService.java`
- `src/main/java/my/side/trading/core/application/operation/OperationsKpiSnapshot.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionGuard.java`
- `src/main/java/my/side/trading/adapter/in/web/dashboard/DashboardController.java`
- `src/main/java/my/side/trading/adapter/in/web/dashboard/dto/DashboardResponse.java`
- `src/main/java/my/side/trading/core/infrastructure/config/TradingOperationProps.java`

## 4. 검증 결과

- 실행한 검증: `./gradlew test`
- 결과: 성공
- 확인 시각: 2026-04-02 KST

참고:

- 테스트 로그에서 Mockito agent 경고가 보인다.
- `TradingApplicationTests`는 로컬 MySQL에 연결한다.
- KPI 집계는 현재 저장된 Job/Order 이력 기반의 최소 모델이며, 장기 운영용 집계 테이블이나 별도 이벤트 적재는 아직 없다.

## 5. 다음 세션에서 우선 볼 항목

이번 세션 전 handoff에 있던 상위 3개 기능은 모두 완료됐다. 다음 세션에서는 `docs/CURRENT_IMPLEMENTATION_SYNC.md` 기준 남은 갭 중 아래 순서를 우선 검토하는 편이 자연스럽다.

### 5.1 1순위: 실행 리스크 한도 모델 구체화

목표:

- 문서에만 있는 실행 리스크 한도(1회 최대 주문 금액, 1일 최대 회전율, 재시도 총 노출, 허용 슬리피지 상한)를 실제 정책으로 연결

현재 상태:

- KPI breach는 자동 실행 차단까지 연결됐지만, 주문 생성/재시도 단계의 한도 모델은 아직 없다.

권장 시작 브랜치:

- `git switch -c feature/execution-risk-limits`

### 5.2 2순위: 최소 장애 알림 골격

목표:

- EOD 실패, 브로커 장애, 데이터 결측, 미정리 주문, Kill Switch 등 핵심 장애를 운영자가 비동기로 바로 인지할 수 있게 골격 추가

현재 상태:

- KPI/가드 모델은 들어갔지만 알림 채널 추상화와 이벤트 발행 구조는 아직 없다.

권장 시작 브랜치:

- `git switch -c feature/minimal-ops-alerts`

### 5.3 3순위: 문서 동기화

목표:

- `docs/CURRENT_IMPLEMENTATION_SYNC.md`의 해소된 갭을 정리하고, 남은 갭만 다시 우선순위화

현재 상태:

- 현재 sync 문서는 `market-calendar-policy`, `circuit-breaker-stateful`, `ops-kpi-risk-controls`가 아직 미구현인 상태로 남아 있어 최신 코드 기준과 어긋난다.

권장 시작 브랜치:

- `git switch -c docs/implementation-sync-refresh`

## 6. 다음 세션용 체크 포인트

- `master` 기준점은 `abccf3c`다.
- 이번에 추가된 설정 블록:
  - `trading.market-calendar.*`
  - `trading.operation.kpi.*`
- `application.yml` 충돌 가능성은 여전히 높으므로 설정 추가는 블록 단위로 조심해서 넣는 편이 안전하다.
- 자동 실행은 이제 아래 조건 중 하나만 걸려도 차단될 수 있다.
  - 운영 모드 미허용
  - `trading.execution.enabled=false`
  - Kill Switch 활성화
  - 시장 상태가 자동 실행 불가
  - KPI breach
- `DashboardResponse` 응답 shape에 운영 KPI가 추가됐으므로, API 소비자가 있다면 응답 파싱 영향 여부를 먼저 확인해야 한다.
- master에 병합 완료된 feature branch는 로컬에서 정리한 상태다.

## 7. 요약

현재 `master`에는 아래 범위가 통합 완료됐다.

- 운영 모드 기반 실행 가드
- 설정 기반 보안 공개 범위
- 호가 기반 주문가 및 재시도 재산정
- 시장 상태 기반 자동 실행/EOD 제어
- 직전 전략 상태 기반 Circuit Breaker 보강
- 운영 KPI 스냅샷과 KPI breach 기반 자동 실행 차단

다음 세션의 첫 작업은 더 이상 handoff의 기존 3개 기능이 아니라, 남은 운영 완성도 갭 중 어떤 것을 먼저 제품 범위로 끌어올릴지 결정하는 것이다.
