# 개발 로드맵 및 가이드

> 상태: Active
> 기준일: 2026-05-13
> 기준 브랜치: `master`

이 문서는 기준 문서에 정의된 제품, 전략, 운영, 구현 갭을 실제 개발 단위로 연결하기 위한 로드맵이다.

우선 기준 문서:

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/STRATEGY_SPEC.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- `deploy/README.md`

## 1. 현재 구현 상태

현재 백엔드는 운영 API와 공개 API 경계를 분리한 상태다.

- 공통 응답 포맷 `ApiResponse`와 전역 예외 처리 `GlobalExceptionHandler`가 구현돼 있다.
- 운영 API는 `X-API-KEY` 인증과 private rate limit을 통과해야 한다.
- 공개 API는 `/public/api/v1/**` 전용 경로로 분리돼 있고, public rate limit, CORS allowlist, 신뢰 프록시 기반 client IP 해석, 공개 접근 로그를 지원한다.
- `prod` 프로필에서는 `/api/dashboard`, `/api/jobs`, `/execution`, `/kis`, `/actuator`를 공개 경로로 설정하면 기동이 실패한다.
- 운영 Dashboard API는 `/api/dashboard/**` 아래에 요약, 이력, 성과 조회를 제공한다.
- 실행 API는 `/api/jobs/**` 아래에 수동 리밸런싱 미리보기, 수동 리밸런싱, 수동 EOD 계산, Job 재실행, `CONFIRMATION_REQUIRED` 주문 확인을 제공한다.
- 운영 모드 API는 `/api/operations/**` 아래에 현재 모드, 모드 전환, 감사 이력, 파라미터 변경 이력 레지스터를 제공한다.
- KIS 운영 조회 API와 local/dev 전용 진단 API가 분리돼 있다.
- Actuator, 로그 롤링, Discord 알림, Flyway 운영 프로필, 단일 VM 배포 산출물이 준비돼 있다.
- 운영 DB와 애플리케이션 시각 기준은 UTC를 사용하고, 화면 표시는 응답 메타데이터의 timezone을 기준으로 변환한다.

## 2. 닫힌 이전 작업

아래 항목은 이전 로드맵의 미완료 체크리스트였지만 현재 코드 기준으로 완료된 영역이다.

- API 응답 표준화와 예외 처리
- SpringDoc OpenAPI, Validation, Actuator 의존성 추가
- DashboardController와 DTO 정의
- 운영 API Key 필터와 rate limit 필터
- 공개 경로 설정, 공개 API CORS, 공개 접근 로그
- 운영 모드 3단계와 수동/자동 실행 가드 분리
- 대시보드 요약, 이력, 성과 API
- 공개 포트폴리오 요약, 성과 API
- 수동 리밸런싱 미리보기, 수동 리밸런싱, 수동 EOD, Job 재실행, 주문 확인 API
- logback 기반 파일 로그와 에러 로그 분리
- `prod` Flyway, Hibernate `ddl-auto=validate`, UTC JDBC time zone
- 단일 VM 배포 템플릿과 reverse proxy 공개 경계 예시

## 3. 다음 우선순위

### P1. 문서와 프론트엔드 경계 싱크

- Admin Dashboard와 Public Dashboard의 역할을 명확히 분리한다.
- Public Dashboard는 인터넷 공개 가능 화면이며 `/public/api/v1/**`만 호출한다.
- Admin Dashboard는 내부 전용 화면이며 `/api/dashboard`, `/api/jobs`, `/api/operations`, `/kis`, `/actuator` 등 운영 API를 호출한다.
- Admin Dashboard는 SSH tunnel, Tailscale, VPN, 또는 별도 인증 계층 뒤에 둔다.
- 백엔드 저장소는 현재 JSON API를 제공한다. 실제 API 연동 프론트엔드 앱은 아직 없고, Admin Dashboard v1 화면 설계 원본은 `frontend/admin-dashboard/wireframe/index.html`에 정적 HTML로 둔다.
- 프론트엔드 산출물은 Git 관리 효율을 위해 백엔드 코드와 분리해 `frontend/` 하위에서 관리한다.

### P2. Admin Dashboard 수동 실행 flow

수동 실주문은 curl 직접 호출이 아니라 Admin Dashboard의 운영 flow로 만든 뒤 진행한다.

- 실행 전 점검: 운영 모드, `trading.execution.enabled`, Kill Switch, 최신 EOD, 운영 KPI, 미정리 주문, KIS 잔고/시세를 한 화면에서 확인한다.
- 주문 미리보기: `GET /api/jobs/manual-rebalance/preview`로 최신 전략 상태 기준 목표 주문, 예상 수량/금액, 리스크 한도 결과를 운영자가 확인한다.
- 최종 확인: 운영자가 명시 확인한 뒤에만 `POST /api/jobs/manual-rebalance` 또는 `POST /api/jobs/{jobId}/execute`를 호출한다.
- 실행 후 확인: Job/Order 상태, `CONFIRMATION_REQUIRED` 주문 확인 API, 운영 로그와 이력을 확인한다.
- 화면 설계: Figma는 디자인 시스템 참고용으로만 유지하고, 화면 설계와 문구 수정은 `frontend/admin-dashboard/wireframe/index.html`에서 관리한다.
- 첫 수동 실거래 리허설은 주문 크기와 실행 조건을 별도 운영 기록으로 남긴다.

### P3. 성과 해석 체계 고도화

- NAV, 월별 손익, MDD는 API에 노출돼 있으나 운영 해석 기준은 아직 최소 수준이다.
- 수수료, 세금, 장기 보유 비용, 환율 효과를 포함한 성과 해석 기준을 보강한다.
- 성과 KPI는 `AUTO_LIVE` 승격 조건의 직접 입력값보다 사후 분석과 파라미터 재검토 입력값으로 관리한다.

### P4. AUTO_LIVE 전 운영 리허설

- `AUTO_LIVE` 승격은 아직 진행하지 않는다.
- `MANUAL_LIVE`에서 수동 실거래 기록, 주문 확인 절차, 장애 대응, 강등/복구 절차를 먼저 검증한다.
- 최근 5영업일 EOD 성공, 미정리 주문 0건, 중복 Job 0건, 브로커/시세 안정성, 운영자 승인 기록을 승격 전 조건으로 유지한다.

## 4. 개발 단위 제안

다음 기능 브랜치는 아래 순서로 나누는 것이 안전하다.

1. `docs-dashboard-sync`: 현재 문서와 코드 상태를 맞춘다.
2. `admin-dashboard-manual-flow-spec`: Admin Dashboard 수동 실행 화면/API 요구사항을 구체화한다.
3. `manual-rebalance-preview-api`: 주문 미리보기와 리스크 결과를 실행 전 조회할 수 있는 백엔드 API를 추가한다. 완료된 개발 단위다.
4. `admin-dashboard-app`: 내부 전용 Admin Dashboard 앱을 만든다.
5. `public-dashboard-app`: 공개 API만 소비하는 Public Dashboard 앱을 만든다.

## 5. 검증 기준

- 문서 변경만 있는 경우에도 `git diff --check`를 실행한다.
- 코드나 설정 변경이 포함되면 Windows PowerShell 기준 `./gradlew.bat test`를 기본 검증으로 실행한다.
- 배포 산출물 또는 운영 프로필을 바꾸면 `./gradlew.bat test bootJar`까지 확인한다.
- 운영 API, 공개 API, Actuator 경계가 바뀌는 변경은 보안 필터 테스트와 `prod` fail-fast 테스트를 함께 보강한다.
