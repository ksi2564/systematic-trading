# QQQ 자동매매 시스템 현재 구현 싱크 문서

기준 브랜치: `master`  
기준일: 2026-04-02  
검토 범위: 현재 저장소 코드, `application.yml`, 운영/전략 관련 문서

관련 문서:

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/STRATEGY_SPEC.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/DEVELOPER_ROADMAP.md`
- `docs/decisions/001_code_review_security_and_refactoring.md`

## 1. 문서 목적

이 문서는 현재 코드가 실제로 구현한 범위와, 제품/운영 문서가 요구하는 목표 정책 사이의 차이를 관리하기 위한 기준 문서다. 구현 완료 범위는 이 문서에서 닫고, 실제 미구현 항목만 후속 우선순위로 남긴다.

## 2. 현재 구현 요약

현재 코드에는 아래 범위가 반영돼 있다.

- 운영 모드 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`
- 실행 트리거 `MANUAL`, `AUTOMATED`
- 실행 가드와 차단 사유 코드화
- QQQ 종가 기반 전략 상태 계산과 저장
- DD / phase / target weights 계산
- 상태 기반 Circuit Breaker
- 리밸런싱 판단과 주문 인텐트 생성
- Job 단위 주문 생성 / 저장 / 실행 / 부분 체결 / 취소 / 재시도
- 시장 캘린더 기반 EOD / 자동 리밸런싱 제어
- 운영 KPI 스냅샷과 자동 실행 KPI breach 차단
- 실행 리스크 한도 4종
- 최소 장애 알림 이벤트 모델, dedupe, 로그 채널, Discord webhook 채널
- 대시보드 요약 / Job 이력 조회
- API Key 인증과 레이트 리밋

핵심 기준 소스:

- 운영 가드: `ExecutionGuard`
- 가격 / 재시도: `ExecutionOrderFactory`, `RetryableOrderExecutor`
- 시장 캘린더: `MarketCalendarService`, `StrategyEodScheduler`, `RebalanceExecutionScheduler`
- Circuit Breaker: `CircuitBreakerService`, `RebalanceDecisionService`, `RebalanceOrchestrator`
- KPI / 리스크 한도: `OperationsKpiService`, `ExecutionRiskLimitService`
- 운영 알림: `OpsAlert`, `OpsAlertPublisher`, `DeduplicatingOpsAlertPublisher`

## 3. 구현 완료 항목

### 운영 모드 / 실행 가드

- 운영 모드 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`가 구현돼 있다.
- 실행 트리거 `MANUAL`, `AUTOMATED`가 구분돼 있다.
- `PAPER`에서는 실주문이 차단된다.
- `MANUAL_LIVE`에서는 수동 실주문만 허용되고 자동 실주문은 차단된다.
- `AUTO_LIVE`에서만 자동 실주문이 허용된다.
- `trading.execution.enabled`, Kill Switch, KPI breach가 자동 실행 차단 조건으로 연결돼 있다.
- 대시보드 응답에 실행 가드 스냅샷이 포함된다.

### 가격 정책 / 재시도

- 최근 체결가 중심 구조가 `lastPrice`, `bestBid`, `bestAsk` 기반으로 확장돼 있다.
- 매수는 최우선 매도호가, 매도는 최우선 매수호가 기준으로 주문 가격을 계산한다.
- 퍼센트 버퍼가 아니라 tick 기반 가격 정책을 사용한다.
- 재시도 시마다 최신 호가를 다시 조회해 주문가를 재산정한다.
- 재시도 횟수와 대기 시간은 설정으로 외부화돼 있다.

### 시장 캘린더

- 시장 상태 `REGULAR`, `HOLIDAY`, `EARLY_CLOSE`, `DATA_UNCERTAIN`가 구현돼 있다.
- 자동 리밸런싱은 `REGULAR`에서만 진입한다.
- scheduled EOD는 `REGULAR`, `EARLY_CLOSE`에서만 수행한다.
- `HOLIDAY`, `DATA_UNCERTAIN`에서는 scheduled EOD와 자동 리밸런싱을 skip한다.
- 시장일자는 `America/New_York` 기준으로 해석한다.
- 휴장일, 조기폐장일, 데이터 미확정일은 `trading.market-calendar.*` 설정으로 관리한다.

### Stateful Circuit Breaker

- `RebalanceOrchestrator`가 최신 전략 상태 외에 직전 전략 상태를 함께 조회한다.
- `RebalanceDecisionService`가 직전 상태의 `targetWeights`를 `prevWeights`로 전달받는다.
- 200MA 필터를 먼저 적용한 뒤 VIX 필터를 적용한다.
- VIX 트리거 시 `TQQQ` 비중이 직전보다 공격적으로 커지지 않도록 제한한다.
- 직전 비중이 없으면 예외 대신 경고 로그와 안전한 fallback으로 처리한다.

### KPI / 실행 리스크 한도

- 운영 KPI 스냅샷이 구현돼 있고 대시보드 요약에 포함된다.
- 현재 KPI 집계 범위:
  - 최신 EOD 성공 여부
  - 중복 `signalDate` Job 정황
  - 미정리 주문 수
  - 주문 실패율
- 자동 실행은 KPI breach가 있으면 `KPI_BREACH`로 차단된다.
- 수동 실행은 KPI breach만으로 차단되지 않는다.
- 실행 리스크 한도 4종이 구현돼 있다.
  - 1회 최대 주문 금액
  - 1일 최대 회전율
  - 재시도 총 노출 한도
  - 허용 슬리피지 상한
- 위반 시 `RISK_LIMIT_BREACH`로 표준화하고 현재 run을 보수적으로 중단 또는 잔여 주문 skip 처리한다.

### 최소 장애 알림

- `OpsAlert` 이벤트 모델과 `OpsAlertPublisher` 포트가 구현돼 있다.
- dedupe 책임은 상위 퍼블리셔에서 수행한다.
- 퍼블리셔 구조는 아래와 같이 분리돼 있다.
  - `DeduplicatingOpsAlertPublisher`
  - `CompositeOpsAlertPublisher`
  - `LoggingOpsAlertPublisher`
  - `DiscordOpsAlertPublisher`
- dedupe는 `dedupeKey + TTL` 기준으로 작동한다.
- 알림 실패는 본 흐름을 실패시키지 않는다.
- 현재 발행 지점:
  - EOD skip / `DATA_UNCERTAIN`
  - EOD failure
  - Kill Switch 차단
  - 자동 실행 KPI breach
  - 중복 `signalDate` Job
  - planned / runtime risk limit breach
  - broker API failure
  - partial / unresolved order
- 로그 채널은 `WARN`, `ERROR` 모두 기록한다.
- Discord webhook 채널은 `ERROR` 이상만 전송한다.
- Discord 연동은 `Incoming Webhook` 방식이며 설정은 `trading.operation.alerts.discord.*`로 관리한다.

### 보안 공개 경로

- 보안 필터는 `/*` 전체 경로에 적용된다.
- 공개 경로는 하드코딩이 아니라 `trading.security.public-path-prefixes` 설정으로 제어한다.
- 기본값은 공개 경로 없음이다.

## 4. 남아 있는 정책-구현 갭

아래 항목은 현재 기준으로도 실제 미구현 또는 미완성인 영역이다.

### P1. 운영 환경 공개 경로 재조정

- 현재 코드는 공개 경로를 설정으로 제어할 수 있지만, 운영 환경에서 어떤 경로를 어디까지 공개할지에 대한 환경별 강제 정책은 없다.
- 대시보드와 Actuator를 운영 환경에서 항상 비공개로 강제하거나, 프록시/VPN/추가 인증과 결합하는 제품 수준 보호는 아직 미구현이다.

### P2. 운영 강등 / 승격 이력과 감사 로그

- 운영 모드와 자동 실행 가드는 구현돼 있다.
- 하지만 `AUTO_LIVE` 승격/강등 이력, 승인 기록, 운영 감사 로그 모델은 없다.
- 현재는 차단 조건이 실행에 반영될 뿐, “누가 언제 왜 전환했는지”를 구조적으로 남기지 않는다.

### P2. 성과 측정 체계

- 현재 KPI는 운영 안전 중심의 최소 모델이다.
- NAV, 월별 PnL, MDD, 성과 스냅샷, 성과 리포팅 모델은 없다.
- 환율, 세금, 장기 보유 비용을 성과 해석 체계에 연결하는 구조도 아직 없다.

### P3. 파라미터 변경 레지스터

- 핵심 전략 파라미터 변경 이력, 변경자, 변경 근거, 검증 결과를 함께 관리하는 레지스터가 없다.
- 현재는 설정 변경과 코드 변경 이력에 의존한다.

## 5. 현재 문서 기준으로 닫힌 항목

아래 항목은 이전 sync 문서에서는 갭으로 남아 있었지만, 현재 코드는 이미 반영 완료된 상태다.

- 운영 모드 3단계 부재
- 자동 / 수동 실행 가드 분리 부재
- 가격 정책의 호가 기반 전환 부재
- 재시도 시 주문 가격 미갱신
- 시장 캘린더 / 데이터 미확정 정책 부재
- VIX stateful Circuit Breaker 부재
- 운영 KPI 스냅샷 부재
- KPI breach 기반 자동 실행 차단 부재
- 실행 리스크 한도 4종 부재
- 최소 장애 알림 골격 부재

## 6. 다음 우선순위

현재 기준에서 후속 구현 우선순위는 아래가 합리적이다.

1. 운영 환경 공개 경로 재조정
2. 운영 강등 / 승격 이력 및 감사 로그
3. 성과 측정 체계(NAV / PnL / MDD)
4. 파라미터 변경 레지스터

로드맵 관리 기준 문서는 `docs/DEVELOPER_ROADMAP.md`다.
