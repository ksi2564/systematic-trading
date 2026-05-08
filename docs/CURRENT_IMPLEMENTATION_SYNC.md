# QQQ 자동매매 시스템 현재 구현 싱크 문서

기준 브랜치: `master`
기준 일자: 2026-05-08
검토 범위: 현재 저장소 코드, `application.yml`, `application-prod.yml`, 운영/전략/배포 문서

관련 문서:

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/STRATEGY_SPEC.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/DEVELOPER_ROADMAP.md`
- `deploy/README.md`
- `docs/decisions/001_code_review_security_and_refactoring.md`
- `docs/decisions/002_public_path_boundary_declaration.md`
- `docs/decisions/003_low_cost_single_cloud_deployment.md`
- `docs/decisions/004_kis_http_policy.md`

## 1. 문서 목적

이 문서는 현재 코드가 실제로 구현한 범위와 제품/운영 문서가 요구하는 목표 정책 사이의 차이를 관리하기 위한 기준 문서다.

구현 완료 범위는 문서에서 닫고, 실제 미구현 항목만 후속 우선순위로 남긴다. 문서 내용과 코드가 다르면 코드와 운영 배포 상태를 우선 확인한 뒤 이 문서를 갱신한다.

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
- KIS 주문 접수 불명확 상태의 `CONFIRMATION_REQUIRED` 처리와 수동 확인 API
- 시장 캘린더 기반 EOD / 자동 리밸런싱 제어
- 운영 KPI 스냅샷과 자동 실행 KPI breach 차단
- 실행 리스크 한도 4종
- 최소 장애 알림 이벤트 모델, dedupe, 로그 채널, Discord webhook 채널
- 운영 모드 감사 로그, 수동 전환 API, 자동 강등 이력
- 파라미터 변경 이력 레지스터
- 운영 Dashboard 요약 / 이력 / 성과 조회 API
- 공개 포트폴리오 요약 / 성과 읽기 API
- API Key 필수 설정 기반 인증과 private/public rate limit
- 공개 경로 보호 선언, CORS allowlist, 신뢰 프록시 기반 client IP 해석, 공개 접근 로그
- 단일 VM 배포용 `prod` 프로필과 서버 산출물 예시
- Flyway 운영 프로필, Hibernate `ddl-auto=validate`, UTC JDBC time zone

핵심 구현 포인트:

- 운영 가드: `ExecutionGuard`
- 가격/재시도: `ExecutionOrderFactory`, `RetryableOrderExecutor`
- 주문 확인: `ExecutionOrderConfirmationService`, `KisOrderInquiry`
- 시장 캘린더: `MarketCalendarService`, `StrategyEodScheduler`, `RebalanceExecutionScheduler`
- Circuit Breaker: `CircuitBreakerService`, `RebalanceDecisionService`, `RebalanceOrchestrator`
- KPI / 리스크 한도: `OperationsKpiService`, `ExecutionRiskLimitService`
- 운영 알림: `OpsAlert`, `OpsAlertPublisher`, `DeduplicatingOpsAlertPublisher`
- 운영 모드 감사: `OperatingModeService`, `OperatingModeController`
- 공개 API: `PublicPortfolioController`, `PublicPortfolioReadService`
- 보안 필터: `ApiKeyAuthFilter`, `RateLimitFilter`, `PublicReadCorsFilter`, `PublicReadAccessLogFilter`

## 3. 구현 완료 항목

### 운영 모드 / 실행 가드

- 운영 모드 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`가 구현돼 있다.
- 실행 트리거 `MANUAL`, `AUTOMATED`가 구분돼 있다.
- `PAPER`에서는 실주문이 차단된다.
- `MANUAL_LIVE`에서는 수동 실주문만 허용되고 자동 실주문은 차단된다.
- `AUTO_LIVE`에서만 자동 실주문이 허용된다.
- `trading.execution.enabled`, Kill Switch, KPI breach가 실행 차단 조건으로 연결돼 있다.
- 대시보드 응답에는 실행 가드와 운영 모드 스냅샷이 포함된다.

### 주문 실행 / 확인 정책

- 최근 체결가와 호가 기반 가격 정책이 구현돼 있다.
- 재시도 시 최신 호가를 다시 조회해 주문가를 재산정한다.
- 주문 접수 API timeout 또는 network error는 미접수 실패로 단정하지 않고 `CONFIRMATION_REQUIRED`로 남길 수 있다.
- `POST /api/jobs/{jobId}/orders/{orderId}/confirm`은 브로커 조회로 주문 접수 여부를 확인한다.
- KIS 주문성 API에는 WebClient/Reactor 자동 retry를 추가하지 않는 정책을 따른다.

### 시장 캘린더 / 시간 기준

- 시장 상태 `REGULAR`, `HOLIDAY`, `EARLY_CLOSE`, `DATA_UNCERTAIN`가 구현돼 있다.
- 자동 리밸런싱은 `REGULAR`에서만 진입한다.
- scheduled EOD는 `REGULAR`, `EARLY_CLOSE`에서만 수행된다.
- 시장 일자는 `America/New_York` 기준으로 해석한다.
- 실행 Job/Order 시각은 도메인/API에서 UTC `Instant`로 다룬다.
- MySQL 운영 DB URL과 Hibernate JDBC time zone은 UTC 기준을 유지한다.
- Dashboard history는 표시용 timezone 메타데이터를 함께 제공한다.

### KPI / 실행 리스크 한도

- 운영 KPI 스냅샷이 구현돼 있고 대시보드 요약에 포함된다.
- 현재 KPI 집계 범위는 최신 EOD 성공 여부, 중복 `signalDate` Job, 미정리 주문 수, 주문 실패율이다.
- 자동 실행은 KPI breach가 있으면 `KPI_BREACH`로 차단된다.
- 수동 실행은 KPI breach만으로 차단하지 않는다.
- 실행 리스크 한도 4종이 구현돼 있다.
- 위반 시 `RISK_LIMIT_BREACH`로 표시하고 현재 run을 보수적으로 중단하거나 후속 주문을 skip 처리한다.

### 운영 API / Admin Dashboard 백엔드

- `GET /api/dashboard/summary`는 포트폴리오, 전략 상태, 보조 지표, 운영 KPI, 성과 요약, 실행 가드, 최근 Job, 운영 감사 이력을 노출한다.
- `GET /api/dashboard/history`는 Job 이력, 운영 모드 감사 이력, 최근 성과 스냅샷을 함께 노출한다.
- `GET /api/dashboard/performance`는 성과 요약, 최근 일별 NAV/DD 시계열, 월별 손익 이력을 함께 노출한다.
- `POST /api/jobs/manual-rebalance`, `POST /api/jobs/eod-calculation`, `POST /api/jobs/{jobId}/execute`, `POST /api/jobs/{jobId}/orders/{orderId}/confirm`이 구현돼 있다.
- `GET /api/operations/mode`, `POST /api/operations/mode`, `GET /api/operations/mode-history`가 구현돼 있다.
- 파라미터 변경 이력 레지스터 API가 구현돼 있다.
- 이 API들은 Admin Dashboard가 소비할 내부 운영 API다.

### 공개 포트폴리오 읽기 API / Public Dashboard 백엔드

- `GET /public/api/v1/summary`는 최신 EOD 스냅샷 기준 공개용 요약 성과와 보유 비중만 노출한다.
- `GET /public/api/v1/performance`는 절대 금액 없이 정규화 인덱스와 월별 수익률만 노출한다.
- 공개 API는 운영 대시보드(`/api/dashboard/**`)를 재사용하지 않는다.
- 공개 응답에는 NAV, 현금 금액, 주문 이력, 운영 감사 이력, 실행 가드, 운영 모드를 포함하지 않는다.
- Public Dashboard는 이 공개 API만 소비하는 외부 공개 화면으로 분리해야 한다.

### 보안 공개 경로 / API Key

- 보안 필터는 `/*` 전체 경로에 적용된다.
- 공개 경로는 하드코딩이 아니라 `trading.security.public-path-prefixes` 설정으로 제어한다.
- 공개 경로를 하나라도 열면 `trading.security.public-path-protection-mode`와 `trading.security.public-path-protection-note`를 함께 명시해야 한다.
- `trading.security.api-key`는 필수 설정이며, 값이 없거나 공백이면 애플리케이션이 기동하지 않는다.
- 공개 경로도 무제한으로 열리지 않고 별도 public rate limit이 적용된다.
- 공개 읽기 API의 CORS 허용 origin은 `trading.security.public-read-allowed-origins`로 제한한다.
- `REVERSE_PROXY` 모드에서는 `trading.security.public-trusted-proxy-ranges`를 반드시 설정해야 한다.
- `prod` 프로필에서는 `/api/dashboard`, `/api/jobs`, `/execution`, `/kis`, `/actuator`를 공개 경로로 설정하면 기동 시 실패한다.

### 운영 배포 / DB 마이그레이션

- `application-prod.yml`은 단일 VM 운영값을 기준으로 정리돼 있다.
- Spring Boot는 `127.0.0.1:8080`, MySQL은 `127.0.0.1:3306`에 바인딩하는 운영 토폴로지를 전제로 한다.
- reverse proxy는 `/public/api/v1/**`만 외부 공개하고 운영 API와 Actuator는 내부 경로로 제한한다.
- 운영 프로필은 Flyway를 사용하고 Hibernate는 `ddl-auto=validate`로만 검증한다.
- 신규 스키마/데이터 보정은 명시 migration으로 추가한다.
- 운영 시각 보정 기준은 UTC이며, V4 migration은 실행 Job/Order 시각만 보정 대상으로 둔다.

## 4. 남아 있는 정책-구현 갭

### P1. 별도 프론트엔드 애플리케이션 부재

- 현재 저장소에는 별도 Admin Dashboard 또는 Public Dashboard 프론트엔드 앱이 없다.
- 백엔드는 JSON API를 제공하며, 화면 구현은 후속 작업이다.
- Public Dashboard는 `/public/api/v1/**`만 호출해야 한다.
- Admin Dashboard는 내부 전용 화면으로 `/api/dashboard`, `/api/jobs`, `/api/operations`, `/kis`, `/actuator` 등 운영 API를 호출해야 한다.

### P2. Admin Dashboard 수동 실주문 flow 부재

- 현재 `POST /api/jobs/manual-rebalance`는 운영자용 화면 없이 실행 API만 제공한다.
- curl 직접 호출은 비상/개발자 운영 경로로만 본다.
- 실제 수동 실주문 전에는 Admin Dashboard에 실행 전 점검, 주문 미리보기, 최종 확인, 실행 후 확인 flow를 먼저 만들어야 한다.
- 주문 미리보기와 리스크 결과를 실행 전에 조회하는 전용 API는 아직 없다.

### P3. 성과 해석 체계 고도화 필요

- NAV, 월별 손익, MDD API는 추가됐지만 운영 해석 기준은 아직 최소 수준이다.
- 수수료, 세금, 장기 보유 비용, 환율 효과를 포함한 장기 성과 해석 체계는 후속 작업이다.

### P4. AUTO_LIVE 전 운영 리허설 필요

- `AUTO_LIVE`는 아직 보수적으로 이르다.
- `MANUAL_LIVE` 수동 실거래 운영 기록, 주문 확인 절차, 장애 대응, 강등/복구 절차 검증이 먼저 필요하다.
- 최근 5영업일 EOD 성공, 미정리 주문 0건, 중복 Job 0건, 브로커/시세 안정성, 운영자 승인 기록을 승격 전 조건으로 유지한다.

## 5. 운영 가능 수준 판단

- `PAPER`
  - 운영 가능에 가깝다.
  - 남은 일은 앱 밖 인프라 보호, 백업, 운영 점검에 가깝다.
- `MANUAL_LIVE`
  - 조건부 가능이다.
  - 현재 운영 모드 전환과 가드는 준비됐지만, 실제 수동 실주문은 Admin Dashboard flow와 운영 리허설을 먼저 갖춘 뒤 진행하는 것이 안전하다.
- `AUTO_LIVE`
  - 아직 대상이 아니다.
  - 수동 실거래 기록과 운영 리허설이 먼저 필요하다.

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
- 운영 배포 산출물 부재
- Flyway 운영 프로필과 UTC Job/Order 시각 기준 부재

## 7. 다음 우선순위

현재 기준에서 후속 구현 우선순위는 아래가 합리적이다.

1. 문서와 프론트엔드 개발 기준 싱크 완료
2. Admin Dashboard 수동 실행 flow 요구사항 구체화
3. 수동 리밸런싱 주문 미리보기 / 리스크 결과 API 추가
4. 내부 전용 Admin Dashboard 앱 구현
5. 공개 API만 소비하는 Public Dashboard 앱 구현
6. `MANUAL_LIVE` 수동 실거래 리허설과 운영 기록 축적
7. 성과 해석 체계 고도화
8. `AUTO_LIVE` 승격 전 체크리스트 검증

로드맵 관리 기준 문서는 `docs/DEVELOPER_ROADMAP.md`다.
