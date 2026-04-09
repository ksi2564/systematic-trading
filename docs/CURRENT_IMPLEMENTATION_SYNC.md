# QQQ 자동매매 시스템 현재 구현 싱크 문서

기준 브랜치: `master`  
기준 일자: 2026-04-07  
검토 범위: 현재 저장소 코드, `application.yml`, 운영/전략 관련 문서

관련 문서:

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/STRATEGY_SPEC.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/DEVELOPER_ROADMAP.md`
- `docs/decisions/001_code_review_security_and_refactoring.md`

## 1. 문서 목적

이 문서는 현재 코드가 실제로 구현한 범위와 제품/운영 문서가 요구하는 목표 정책 사이의 차이를 관리하기 위한 기준 문서다. 구현 완료 범위는 문서에서 닫고, 실제 미구현 항목만 후속 우선순위로 남긴다.

## 2. 현재 구현 요약

현재 코드에는 아래 범위가 반영돼 있다.

- 운영 모드 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`
- 실행 트리거 `MANUAL`, `AUTOMATED`
- 실행 차단 사유 코드화
- QQQ 종가 기반 전략 상태 계산과 저장
- DD / phase / target weights 계산
- 상태 기반 Circuit Breaker
- 리밸런싱 판단과 주문 템플릿 생성
- Job 단위 주문 생성 / 저장 / 실행 / 부분체결 / 취소 / 재시도
- 시장 캘린더 기반 EOD / 자동 리밸런싱 제어
- 운영 KPI 스냅샷과 자동 실행 KPI breach 차단
- 실행 리스크 한도 4종
- 최소 장애 알림 이벤트 모델, dedupe, 로그 채널, Discord webhook 채널
- 운영 모드 감사 로그, 수동 전환 API, 자동 강등 이력
- 대시보드 요약 / Job 이력 조회
- 공개 포트폴리오 요약 / 성과 읽기 API
- API Key 필수 설정 기반 인증과 레이트 리밋

핵심 구현 포인트:

- 운영 가드: `ExecutionGuard`
- 가격/재시도: `ExecutionOrderFactory`, `RetryableOrderExecutor`
- 시장 캘린더: `MarketCalendarService`, `StrategyEodScheduler`, `RebalanceExecutionScheduler`
- Circuit Breaker: `CircuitBreakerService`, `RebalanceDecisionService`, `RebalanceOrchestrator`
- KPI / 리스크 한도: `OperationsKpiService`, `ExecutionRiskLimitService`
- 운영 알림: `OpsAlert`, `OpsAlertPublisher`, `DeduplicatingOpsAlertPublisher`
- 운영 모드 감사: `OperatingModeService`, `OperatingModeController`

## 3. 구현 완료 항목

### 운영 모드 / 실행 가드

- 운영 모드 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`가 구현돼 있다.
- 실행 트리거 `MANUAL`, `AUTOMATED`가 구분돼 있다.
- `PAPER`에서는 실주문이 차단된다.
- `MANUAL_LIVE`에서는 수동 실주문만 허용되고 자동 실주문은 차단된다.
- `AUTO_LIVE`에서만 자동 실주문이 허용된다.
- `trading.execution.enabled`, Kill Switch, KPI breach가 자동 실행 차단 조건으로 연결돼 있다.
- 대시보드 응답에는 실행 가드와 운영 모드 스냅샷이 포함된다.

### 가격 정책 / 재시도

- 최근 체결가 조회 구조가 `lastPrice`, `bestBid`, `bestAsk` 기반으로 확장돼 있다.
- 매수는 최우선 매도호가, 매도는 최우선 매수호가 기준으로 주문 가격을 계산한다.
- 퍼센트 버퍼가 아니라 tick 기반 가격 보정을 사용한다.
- 재시도 시점에는 최신 호가를 다시 조회해 주문가를 재산정한다.
- 재시도 횟수와 대기 시간은 설정으로 제어된다.

### 시장 캘린더

- 시장 상태 `REGULAR`, `HOLIDAY`, `EARLY_CLOSE`, `DATA_UNCERTAIN`가 구현돼 있다.
- 자동 리밸런싱은 `REGULAR`에서만 진입한다.
- scheduled EOD는 `REGULAR`, `EARLY_CLOSE`에서만 수행된다.
- `HOLIDAY`, `DATA_UNCERTAIN`에서는 scheduled EOD와 자동 리밸런싱을 skip한다.
- 시장 일자는 `America/New_York` 기준으로 해석한다.
- 휴장일, 조기마감일, 데이터 미확정일은 `trading.market-calendar.*` 설정으로 관리한다.

### Stateful Circuit Breaker

- `RebalanceOrchestrator`가 최신 전략 상태 조회 전에 직전 전략 상태를 함께 조회한다.
- `RebalanceDecisionService`가 직전 상태의 `targetWeights`를 `prevWeights`로 전달받는다.
- 200MA 필터를 먼저 적용한 뒤 VIX 필터를 적용한다.
- VIX 트리거는 `TQQQ` 비중을 직전보다 공격적으로 키우지 않도록 제한한다.
- 직전 비중이 없으면 예외 대신 경고 로그와 안전한 fallback으로 처리한다.

### KPI / 실행 리스크 한도

- 운영 KPI 스냅샷이 구현돼 있고 대시보드 요약에 포함된다.
- 현재 KPI 집계 범위:
  - 최신 EOD 성공 여부
  - 중복 `signalDate` Job 탐지
  - 미정리 주문 수
  - 주문 실패율
- 자동 실행은 KPI breach가 있으면 `KPI_BREACH`로 차단된다.
- 수동 실행은 KPI breach만으로 차단하지 않는다.
- 실행 리스크 한도 4종이 구현돼 있다.
  - 1회 최대 주문 금액
  - 1일 최대 회전율
  - 재시도 총 노출 한도
  - 허용 슬리피지 상한
- 위반 시 `RISK_LIMIT_BREACH`로 표시하고 현재 run을 보수적으로 중단하거나 후속 주문을 skip 처리한다.

### 최소 장애 알림

- `OpsAlert` 이벤트 모델과 `OpsAlertPublisher` 포트가 구현돼 있다.
- dedupe 책임은 상위 퍼블리셔에서 수행한다.
- 퍼블리셔 구조는 아래와 같이 분리돼 있다.
  - `DeduplicatingOpsAlertPublisher`
  - `CompositeOpsAlertPublisher`
  - `LoggingOpsAlertPublisher`
  - `DiscordOpsAlertPublisher`
- dedupe는 `dedupeKey + TTL` 기준으로 동작한다.
- 알림 전송 실패는 본 흐름을 실패시키지 않는다.
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
- Discord 연동은 `Incoming Webhook` 방식이고 설정은 `trading.operation.alerts.discord.*`로 관리한다.

### 보안 공개 경로 / API key

- 보안 필터는 `/*` 전체 경로에 적용된다.
- 공개 경로는 하드코딩이 아니라 `trading.security.public-path-prefixes` 설정으로 제어한다.
- 공개 경로를 하나라도 열면 `trading.security.public-path-protection-mode`와 `trading.security.public-path-protection-note`를 함께 명시해야 한다.
- 보호 모드는 `PRIVATE_NETWORK`, `VPN`, `REVERSE_PROXY`, `ADDITIONAL_AUTH` 중 하나로 선언한다.
- 기본값은 공개 경로 없음이다.
- `trading.security.api-key`는 필수 설정이며, 값이 없거나 공백이면 애플리케이션이 기동하지 않는다.
- 공개 경로도 무제한으로 열리지 않고 별도 public rate limit이 적용된다.
- 공개 읽기 API의 CORS 허용 origin은 `trading.security.public-read-allowed-origins`로 제한한다.
- `REVERSE_PROXY` 모드에서는 `trading.security.public-trusted-proxy-ranges`를 반드시 설정해야 한다.
- 공개 경로의 client IP 해석은 신뢰 프록시 범위 안에서만 `trading.security.public-client-ip-header` 값을 사용한다.
- `trading.security.public-access-log-enabled=true`면 공개 읽기 API 접근 로그를 별도 logger로 남긴다.
- `prod` 프로필에서는 `/api/dashboard`, `/api/jobs`, `/execution`, `/kis`, `/actuator`를 공개 경로로 설정하면 기동 시 실패한다.
- `prod`에서 공개 읽기 API를 열면 `TRADING_PUBLIC_READ_ALLOWED_ORIGINS`, `TRADING_PUBLIC_TRUSTED_PROXY_RANGES` 환경변수로 실제 운영값을 넣어야 한다.
- `prod`에서는 wildcard origin, path가 포함된 origin, `example.com` 계열 샘플 origin, `10.0.0.0/8` 샘플 CIDR을 두면 기동 시 실패한다.

### 운영 모드 감사 / 전환 API

- 현재 운영 모드는 `trading_control.OPERATING_MODE`에 저장된다.
- DB 값이 없으면 `trading.operation.mode`를 bootstrap 기본값으로 사용한다.
- bootstrap 값이 `AUTO_LIVE`이고 수동 승인 기록이 필수면 안전하게 `MANUAL_LIVE`로 내려 시작하고 감사 로그를 남긴다.
- `operation_mode_audit` append-only 테이블에 승격/강등/안전 override 이력이 저장된다.
- `AUTO_LIVE` 수동 진입은 승인 기록으로 적재되며, `requestedBy`, `reason`, `approvedBy`, `approvedAt`을 함께 남긴다.
- `GET /api/operations/mode`, `POST /api/operations/mode`, `GET /api/operations/mode-history` 운영 API가 구현돼 있다.
- 대시보드 요약은 현재 DB 운영 모드와 최근 운영 감사 이력 5건을 함께 노출한다.
- `GET /api/dashboard/history`는 최신순 Job 이력, 운영 모드 감사 이력, 최근 성과 스냅샷을 함께 노출한다.
- `GET /api/dashboard/performance`는 성과 요약, 최근 일별 NAV/DD 시계열, 월별 손익 이력을 함께 노출한다.
- `GET /public/api/v1/summary`는 최신 EOD 스냅샷 기준 공개용 요약 성과와 보유 비중만 노출한다.
- `GET /public/api/v1/performance`는 절대 금액 없이 정규화 인덱스와 월별 수익률만 노출한다.
- 시스템 이벤트 `KPI_BREACH`, `DATA_UNCERTAIN`, `EOD_FAILURE`, `UNRESOLVED_ORDER`, `RISK_LIMIT_BREACH`, `BROKER_API_FAILURE`, `KILL_SWITCH_ON`은 현재 모드가 `AUTO_LIVE`일 때 자동 강등으로 연결된다.

### 파라미터 변경 이력 레지스터

- `ParameterRegistryService`가 핵심 파라미터 레지스터를 bootstrap / 조회 / 변경 이력 적재까지 관리한다.
- `PropertyBasedEffectiveParameterSnapshotProvider`가 현재 설정값을 레지스터 기준값으로 읽기 쉬운 문자열로 노출한다.
- `GET /api/operations/parameter-registry`, `GET /api/operations/parameter-registry/history`, `POST /api/operations/parameter-registry/history`가 구현돼 있다.
- 변경 기록에는 변경자, 사유, 상태, 근거, 검증 방법, 검증 요약, 다음 재검토일, 관련 산출물이 함께 저장된다.

## 4. 남아 있는 정책-구현 갭

아래 항목은 현재 기준으로 실제 미구현이거나 미완성인 영역이다.

### P1. 운영 환경 공개 경로 보호

- 공개 경로를 열려면 애플리케이션 설정에 외부 보호 계층과 운영 메모를 명시해야 한다.
- 공개 포트폴리오 읽기 API는 애플리케이션 안에서 분리됐고, 신뢰 프록시 allowlist와 public 접근 로그까지 앱 설정으로 보강됐다.
- 다만 reverse proxy / CDN / TLS 인증서 / 실제 WAF 정책 같은 외부 경계는 여전히 인프라 책임으로 남아 있다.
- `prod` 프로필에서 운영 API와 Actuator를 공개 경로로 여는 설정은 기동 시 차단된다.
- 프록시/VPN/추가 인증 자체를 실제로 구성하는 일은 애플리케이션 밖 인프라 책임으로 남아 있다.

### P2. 성과 측정 체계

- 현재 KPI는 운영 안전 중단용 최소 모델이다.
- NAV, 누계 PnL, MDD, 성과 리포트 API는 추가됐지만 해석 기준은 아직 최소 수준이다.
- 수수료, 세금, 장기 보유 비용까지 포함한 성과 해석 체계도 아직 없다.

## 5. 운영 가능 수준 판단

현재 코드와 운영 문서 기준으로 보면 운영 가능 수준은 아래처럼 보는 것이 합리적이다.

- `PAPER`
  - 거의 준비 완료다.
  - 남은 일은 앱 밖 인프라 보호, 운영 점검, 배포 환경 검증에 가깝다.
- `MANUAL_LIVE`
  - 조건부로 가능하다.
  - 파라미터 변경 이력 레지스터와 공개용 읽기 API 경계는 들어왔지만, 실거래 운영 기록과 인프라 보호 구성이 더 필요하다.
- `AUTO_LIVE`
  - 아직 보수적으로는 이르다.
  - 수동 실거래 운영 기록, 승격 체크리스트 검증, 인프라 보호 구성이 더 필요하다.

## 6. 현재 문서 기준으로 닫힌 항목

아래 항목은 이전 sync 문서에서는 갭으로 남아 있었지만 현재 코드에는 반영 완료된 상태다.

- 운영 모드 3단계 부재
- 자동 / 수동 실행 가드 분리 부재
- 가격 정책의 호가 기반 전환 부재
- 재시도 시 주문 가격 미갱신 부재
- 시장 캘린더 / 데이터 미확정 정책 부재
- VIX stateful Circuit Breaker 부재
- 운영 KPI 스냅샷 부재
- KPI breach 기반 자동 실행 차단 부재
- 실행 리스크 한도 4종 부재
- 최소 장애 알림 골격 부재
- 운영 모드 감사 로그 / 전환 API 부재
- 파라미터 변경 이력 레지스터 부재
- 공개용 포트폴리오 읽기 API 부재
- API key placeholder fallback 제거 부재

## 7. 다음 우선순위

현재 기준에서 후속 구현 우선순위는 아래가 합리적이다.

1. 공개 운영 환경의 reverse proxy / CDN / TLS / 접근 로그 실제 적용
2. 성과 측정 체계(NAV / PnL / MDD) 고도화
3. `AUTO_LIVE` 승격 전 운영 리허설과 체크리스트 구체화

로드맵 관리 기준 문서는 `docs/DEVELOPER_ROADMAP.md`다.
