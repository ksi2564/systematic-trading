# Handoff - 2026-04-03 Ops Alert / Discord / Sync Refresh

## 1. 목적

이번 세션에서는 운영 알림 체계를 최소 수준으로 확장하고, 현재 구현 상태 문서를 코드 기준으로 다시 맞췄다. 다음 세션에서는 이 문서를 기준으로 미커밋 변경을 정리하거나 후속 운영 기능을 이어서 진행하면 된다.

- 기준 브랜치 상태: `master`
- 기준 HEAD: `9b0e08d`
- 검증 기준: `./gradlew test`
- 현재 상태: 워크트리에 미커밋 변경 있음

관련 문서:

- `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/DEVELOPER_ROADMAP.md`
- `docs/HANDOFF_2026-04-02_MASTER_INTEGRATION.md`

## 2. 이번 세션에서 반영한 내용

### 2.1 CURRENT_IMPLEMENTATION_SYNC 갱신

`docs/CURRENT_IMPLEMENTATION_SYNC.md`를 현재 코드 기준으로 다시 정리했다.

- 운영 모드 / 실행 가드
- 가격 계산 / 재시도 정책
- 시장 캘린더
- VIX stateful circuit breaker
- KPI / risk limits
- 최소 장애 알림

기존 문서에 남아 있던 "최소 장애 알림 부재"는 제거했고, 실제 남은 갭만 다시 정리했다.

현재 문서에 남겨둔 주요 갭:

- 운영 환경 공개 경로 재조정
- 운영 강등/승격 이력과 감사 로그
- 성과 측정 체계(NAV / PnL / MDD)
- 파라미터 변경 레지스터

### 2.2 운영 알림 퍼블리셔 구조 정리

운영 알림 구조를 `dedupe -> fan-out -> channel` 형태로 정리했다.

- `OpsAlert`, `OpsAlertPublisher`, `OpsAlertSeverity`, `OpsAlertType` 추가
- `DeduplicatingOpsAlertPublisher`
  - 글로벌 enable 체크
  - `dedupeKey + TTL` 기준 중복 억제
- `CompositeOpsAlertPublisher`
  - 여러 채널 퍼블리셔 fan-out
- `LoggingOpsAlertPublisher`
  - 로그 출력만 담당
- `DiscordOpsAlertPublisher`
  - Discord Incoming Webhook 전송만 담당

알림 전송 실패는 모두 삼키고 로그만 남기도록 유지했다. EOD / 주문 / execution guard 본 흐름은 깨지지 않는다.

### 2.3 Discord webhook 연동

Discord는 bot/token이 아니라 Incoming Webhook 방식으로 붙였다.

추가/정리한 설정:

- `trading.operation.alerts.enabled`
- `trading.operation.alerts.dedupe-ttl-minutes`
- `trading.operation.alerts.discord.enabled`
- `trading.operation.alerts.discord.webhook-url`
- `trading.operation.alerts.discord.min-severity`

동작 규칙:

- 로그 채널은 `WARN`, `ERROR` 모두 기록
- Discord 채널은 `min-severity` 이상만 전송
- 현재 기본값은 `ERROR`
- webhook URL이 비어 있거나 Discord가 비활성이면 Discord 전송은 skip

Discord payload v1:

- `content`
- embed 1개
- title: `[{severity}] {type}`
- description: `alert.message`
- fields:
  - `dedupeKey`
  - 주요 detail 일부

### 2.4 알림 발행 지점 연결

기존 계획대로 아래 지점에 발행을 연결했다.

- `StrategyEodScheduler`
  - `DATA_UNCERTAIN` skip
  - EOD failure
- `ExecutionGuard`
  - Kill Switch 차단
  - 자동 실행 KPI breach 차단
- `ExecutionJobCreateService`
  - duplicate `signalDate` job
  - planned risk limit breach
- `ExecutionJobExecutor`
  - partial / unresolved order
  - runtime risk limit breach
- `KisOrderBroker`
  - broker API failure

현재 severity 기준:

- `ERROR`
  - `EOD_FAILURE`
  - `BROKER_API_FAILURE`
  - `DATA_UNCERTAIN`
  - `KILL_SWITCH_ON`
  - `KPI_BREACH`
  - `RISK_LIMIT_BREACH`
- `WARN`
  - `UNRESOLVED_ORDER`
  - `DUPLICATE_SIGNAL_JOB_DETECTED`

Discord 전송 여부는 이벤트별 예외 분기 없이 severity threshold로만 판정한다.

## 3. 주요 변경 파일

문서:

- `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- `docs/HANDOFF_2026-04-03_OPS_ALERT_DISCORD_SYNC.md`

운영 알림 도메인 / 퍼블리셔:

- `src/main/java/my/side/trading/core/domain/operation/OpsAlert.java`
- `src/main/java/my/side/trading/core/domain/operation/OpsAlertPublisher.java`
- `src/main/java/my/side/trading/core/domain/operation/OpsAlertSeverity.java`
- `src/main/java/my/side/trading/core/domain/operation/OpsAlertType.java`
- `src/main/java/my/side/trading/adapter/out/operation/DeduplicatingOpsAlertPublisher.java`
- `src/main/java/my/side/trading/adapter/out/operation/CompositeOpsAlertPublisher.java`
- `src/main/java/my/side/trading/adapter/out/operation/LoggingOpsAlertPublisher.java`
- `src/main/java/my/side/trading/adapter/out/operation/DiscordOpsAlertPublisher.java`
- `src/main/java/my/side/trading/adapter/out/operation/OpsAlertChannelPublisher.java`

발행 지점 / 설정:

- `src/main/java/my/side/trading/adapter/in/scheduler/StrategyEodScheduler.java`
- `src/main/java/my/side/trading/adapter/out/kis/order/KisOrderBroker.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionGuard.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionJobCreateService.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionJobExecutor.java`
- `src/main/java/my/side/trading/core/infrastructure/config/TradingOperationProps.java`
- `src/main/resources/application.yml`

테스트:

- `src/test/java/my/side/trading/adapter/out/operation/DiscordOpsAlertPublisherTest.java`
- `src/test/java/my/side/trading/adapter/out/operation/OpsAlertPublisherPipelineTest.java`
- 기존 scheduler / execution / operation 관련 테스트 보정

## 4. 설정 및 운영 메모

### 4.1 secret 로딩

현재 `application.yml`에는 아래 import가 들어 있다.

- `spring.config.import: optional:classpath:application-secret.yml`

따라서 로컬에서는 `src/main/resources/application-secret.yml`에 둔 값이 함께 로딩된다.

이번 세션에서 확인한 점:

- `trading.security.api-key`
- `trading.operation.alerts.discord.webhook-url`

위 두 키는 `application-secret.yml`에 값이 있으면 실제 바인딩 대상에 반영된다.

### 4.2 API key 의미

`trading.security.api-key`는 운영 API용 shared secret이다.

- 헤더: `X-API-KEY`
- 필터: `ApiKeyAuthFilter`
- 공개 경로(`trading.security.public-path-prefixes`)를 제외한 API 호출에 사용

실주문/수동 실행/KIS 관련 운영 API를 보호하는 용도다.

### 4.3 fallback 설정 관련 메모

현재 `application.yml`에는 아래 형태가 남아 있다.

- `trading.security.api-key: ${TRADING_API_KEY:change-me}`
- `trading.operation.alerts.discord.webhook-url: ${TRADING_DISCORD_WEBHOOK_URL:}`

의미:

- `:` 뒤 값은 환경변수 미설정 시 fallback
- `change-me`는 앱 기동 편의를 위한 placeholder
- 빈 문자열 fallback은 Discord를 optional integration으로 두기 위한 처리

다만 `trading.security.api-key`는 운영 필수 secret 성격이 강해서, 후속 세션에서 `change-me` fallback 제거 여부를 결정하는 것이 맞다.

## 5. 검증 결과

### 5.1 테스트

- 실행 명령: `./gradlew test`
- 결과: 성공

추가 검증 범위:

- dedupe가 동일 alert를 한 번만 fan-out 하는지
- composite publisher가 logging + discord 채널을 모두 호출하는지
- `min-severity = ERROR`일 때 WARN이 Discord로 가지 않는지
- Discord webhook 실패가 예외를 전파하지 않는지
- Discord payload shape에 핵심 필드가 들어가는지

### 5.2 Discord 실제 송신 확인

로컬 `application-secret.yml`에 들어 있는 `trading.operation.alerts.discord.webhook-url`을 사용해 테스트 메시지를 1회 전송했고 성공했다.

즉 현재 webhook 값 자체는 유효하고, 네트워크/채널 기준으로도 정상 수신 가능한 상태다.

## 6. 현재 워크트리 상태

미커밋 변경이 남아 있다. 다음 세션에서는 이 상태를 전제로 작업해야 한다.

수정된 파일:

- `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- `src/main/java/my/side/trading/adapter/in/scheduler/StrategyEodScheduler.java`
- `src/main/java/my/side/trading/adapter/out/kis/order/KisOrderBroker.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionGuard.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionJobCreateService.java`
- `src/main/java/my/side/trading/core/application/execution/ExecutionJobExecutor.java`
- `src/main/java/my/side/trading/core/infrastructure/config/TradingOperationProps.java`
- `src/main/resources/application.yml`
- 테스트 파일 다수

신규 파일/디렉터리:

- `src/main/java/my/side/trading/adapter/out/operation/`
- `src/main/java/my/side/trading/core/domain/operation/`
- `src/test/java/my/side/trading/adapter/out/operation/`
- `docs/HANDOFF_2026-04-03_OPS_ALERT_DISCORD_SYNC.md`

## 7. 다음 세션 우선순위

### 7.1 1순위

현재 변경을 검토하고 의미 단위로 커밋할지 결정한다.

- 운영 알림 + Discord 연동
- sync 문서 갱신

위 둘을 한 커밋으로 묶어도 되고, 문서와 코드 변경을 분리해도 된다.

### 7.2 2순위

`trading.security.api-key` fallback 정책을 정리한다.

선택지:

1. `change-me` 유지
   - 장점: 로컬 기동 편의
   - 단점: 운영 누락 발견이 늦을 수 있음
2. fallback 제거
   - 장점: 필수 secret 누락을 기동 시 바로 탐지
   - 단점: 로컬 초기 설정이 조금 더 번거로움

운영 기준으로는 2번이 더 안전하다.

### 7.3 3순위

남은 운영 영역 갭 중 하나를 후속 작업으로 잡는다.

- 운영 공개 경로 재조정
- 운영 모드 강등/승격 감사 로그
- 성과 측정 체계
- 파라미터 변경 레지스터

## 8. 한 줄 요약

이번 세션 결과는 "운영 알림을 로그 전용 수준에서 Discord 병렬 채널까지 확장했고, 현재 구현 문서를 코드와 다시 맞췄다"로 정리할 수 있다. 다음 세션은 이 변경을 커밋 기준으로 정리한 뒤, 보안 secret 정책이나 운영 감사 영역으로 이어가면 된다.
