# QQQ 자동매매 시스템 운영 런북

기준일: 2026-04-02  
대상 독자: 운영자, 개발자  
기준: 현재 저장소 코드(`master`)와 ADR을 기준으로 정리한 운영 기준 문서

관련 문서:

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/STRATEGY_SPEC.md`
- `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- `docs/LOW_COST_DEPLOYMENT_RUNBOOK.md`
- `docs/decisions/001_code_review_security_and_refactoring.md`

## 1. 운영 목적과 기본 원칙

운영의 목적은 전략 상태 계산, 리밸런싱 판단, 주문 실행을 일관된 절차로 수행하되, 비정상 상황에서는 즉시 차단하고 사람이 판단할 수 있는 여지를 남기는 것이다.

기본 원칙은 다음과 같다.

- 실거래 실행은 항상 차단 조건을 먼저 확인한다.
- 자동 실행과 수동 실행은 동일한 보호 장치를 따라야 한다.
- 실행 결과는 추적 가능해야 한다.
- 외부 시세와 브로커 연동 장애는 즉시 운영 이슈로 취급한다.
- 공개 경로는 개발 편의가 아니라 운영 리스크 관점에서 검토한다.

현재 기본 배포 형태는 `저비용 단일 VM + reverse proxy + SSH tunnel`이다. 구체적인 서버 산출물과 배포 절차는 `docs/LOW_COST_DEPLOYMENT_RUNBOOK.md`와 `deploy/README.md`를 기준으로 본다.

## 2. 운영 모드별 허용 행위

운영 모드는 `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE` 세 단계로 구분한다. 이 용어는 제품 요구사항 문서와 동일한 기준으로 사용한다.

| 운영 모드 | 허용 행위 | 자동 실주문 | 수동 실주문 | 기본 사용 목적 |
| :--- | :--- | :--- | :--- | :--- |
| `PAPER` | 계산, 판단, 이력 기록, 운영 점검 | 금지 | 금지 | 전략 검증과 장애 점검 |
| `MANUAL_LIVE` | 계산, 판단, 운영자 수동 실행 | 금지 | 허용 | 승인 기반 실거래 |
| `AUTO_LIVE` | 계산, 판단, 자동/수동 실행 | 허용 | 허용 | 보호 장치 하의 자동 실거래 |

추가 원칙:

- 장애 복구 직후, 시장 일정 불확실성, 데이터 미확정, 미정리 주문 존재 시에는 `AUTO_LIVE`를 유지하지 않는다.
- 운영자가 확신할 수 없는 상태에서는 `MANUAL_LIVE` 또는 `PAPER`로 낮춰 운영하는 것을 기본으로 한다.

### 운영 준비도 기준

아래 표는 "이 모드로 운영을 시작해도 되는가"를 판단하기 위한 최소 기준이다. 현재 구현이 허용하는 기능과, 실제 운영을 시작하기 전에 갖춰야 할 준비 항목을 구분해서 본다.

| 모드 | 운영 목적 | 시작 전 필수 조건 | 권장 추가 조건 | 현재 기준 판단 |
| :--- | :--- | :--- | :--- | :--- |
| `PAPER` | 계산, 판단, 로그, 알림, 대시보드, 스케줄 운영 검증 | API Key/레이트 리밋, EOD/리밸런싱 스케줄 정상 동작, 대시보드/이력 조회 가능, 운영 모드 감사 로그, 장애 알림, 운영 환경 접근 보호 | Actuator/로그 롤링/백업 점검, DST/휴장일 리허설 | 시작 가능에 가깝다. 인프라 접근 보호와 운영 점검만 마치면 된다. |
| `MANUAL_LIVE` | 운영자 승인 기반 실거래 | `PAPER` 기준 충족, 브로커 실주문 경로 검증, 승인 절차와 운영 체크리스트, Kill Switch/강등 절차 점검 | 파라미터 변경 이력 레지스터, 주문/체결 리허설 기록, 복구 훈련 | 조건부 가능이다. 가능하면 파라미터 변경 이력 레지스터까지 반영한 뒤 시작하는 것이 안전하다. |
| `AUTO_LIVE` | 보호 장치 하의 자동 실거래 | `MANUAL_LIVE` 기준 충족, 최근 5영업일 EOD 성공, 미정리 주문 0건, 중복 Job 0건, 브로커/시세 연동 안정, 승격 승인 기록 | 성과 해석 체계 고도화, 자동 재개/강등 리허설, 운영 대시보드 해석 기준 정리 | 아직 보수적으로는 이르다. 수동 실거래 검증과 운영 리허설을 먼저 거친 뒤 검토한다. |

### 현재 운영 권장 순서

1. `PAPER`로 운영 절차와 스케줄, 대시보드, 알림을 검증한다.
2. `MANUAL_LIVE`로 실제 주문과 체결, 승인 절차를 검증한다.
3. 충분한 운영 기록이 쌓인 뒤 `AUTO_LIVE` 승격 조건을 별도로 확인한다.

### 승격 절차

`AUTO_LIVE` 승격은 아래 조건을 모두 만족한 뒤 운영자 1인이 명시 승인할 때만 수행한다.

- 최근 5영업일 연속 EOD 계산 성공
- 미정리 주문 `0건`
- 중복 Job 정황 `0건`
- 브로커 및 핵심 시세 연동 정상
- 시장 상태가 `정규장` 또는 명시 승인된 예외 상태
- `POST /api/operations/mode` 호출로 남겨진 승격 승인 기록 존재

### 강등 절차

`AUTO_LIVE` 운영 중 아래 트리거가 발생하면 자동 실행을 즉시 중지하고 `MANUAL_LIVE` 또는 `PAPER`로 강등한다.

| 트리거 | 즉시 액션 | 기본 강등 방향 |
| :--- | :--- | :--- |
| EOD 계산 실패 | 자동 실행 중지, 원인 확인, 당일 전략 상태 재검증 | `MANUAL_LIVE` |
| 데이터 결측/미확정 | 자동 실행 중지, 데이터 재수집 또는 수동 점검 | `MANUAL_LIVE` 또는 `PAPER` |
| 브로커 장애 또는 주문 API 실패 | 자동 실행 중지, 브로커 상태 확인, 필요 시 Kill Switch 활성화 | `MANUAL_LIVE` 또는 `PAPER` |
| 미정리 주문 또는 부분 체결 지속 | 신규 자동 주문 중지, 기존 주문 정리 | `MANUAL_LIVE` |
| KPI breach | 기준 초과 KPI 확인, 재발 여부 점검 | `MANUAL_LIVE` |

## 3. 일일 운영 흐름

### EOD 계산

1. 장 마감 이후 QQQ 전일 종가를 조회한다.
2. 보조 지표로 VIX와 QQQ 200MA를 조회한다.
3. 전략 상태를 계산하고 저장한다.

### 자동 리밸런싱

1. 운영 모드가 `AUTO_LIVE`인지 확인한다.
2. 스케줄러 활성화 여부를 확인한다.
3. 실행 활성화 여부와 Kill Switch 상태를 확인한다.
4. 최신 전략 상태와 현재 포트폴리오를 조회한다.
5. Circuit Breaker 데이터를 반영해 리밸런싱 필요 여부를 판단한다.
6. 실행 가능한 주문이 있으면 Job을 생성하고 즉시 실행한다.

### 수동 트리거

운영자는 필요 시 아래 항목을 수동으로 실행할 수 있다.

- EOD 계산
- 리밸런싱 전체 실행
- 특정 Job 재실행

수동 실주문은 `MANUAL_LIVE` 또는 `AUTO_LIVE`에서만 허용한다.

## 4. 스케줄 기준

| 작업 | 실행 시각 | 요일 | 기준 시간대 |
| :--- | :--- | :--- | :--- |
| EOD 계산 | 16:15 | 월요일~금요일 | America/New_York |
| 성과 분석 집계 | 16:30 | 월요일~금요일 | America/New_York |
| 자동 리밸런싱 | 09:45 | 월요일~금요일 | America/New_York |

운영 참고:

- 현재 구현은 미국 동부시간 기준으로 장 이벤트 15분 뒤에 실행된다.
- 한국시간으로 보면 자동 리밸런싱은 `22:45` 또는 `23:45`, EOD 계산은 다음 날 `05:15` 또는 `06:15`, 성과 분석 집계는 다음 날 `05:30` 또는 `06:30`에 실행된다.
- 썸머타임/윈터타임 전환은 스케줄러가 `America/New_York` 시간대를 기준으로 자동 반영한다.

## 5. 미국장 캘린더 운영 정책

시장 상태는 `정규장`, `휴장`, `조기폐장`, `데이터 미확정` 네 가지로 분류한다.

### 휴장일

- EOD 계산과 자동 리밸런싱은 모두 skip한다.
- 필요 시 운영자는 상태 확인만 수행하고 실주문은 기본적으로 진행하지 않는다.

### 조기폐장일

- EOD 계산은 시장 데이터 확정 이후에만 수행한다.
- 자동 리밸런싱은 기본적으로 skip하거나 `MANUAL_LIVE`로 전환해 운영자 판단 하에 수행한다.

### DST 변동일

- 현재 스케줄러는 `America/New_York` 시간대를 기준으로 실행되므로 썸머타임/윈터타임 전환이 자동 반영된다.
- 다만 DST 변동 주간에도 휴장일, 조기폐장, 데이터 미확정일 설정은 운영자가 사전에 확인해야 한다.

### 데이터 미확정 또는 결측

- 해당 일자의 자동 실주문은 금지한다.
- 운영 모드는 `PAPER` 또는 `MANUAL_LIVE`로 낮춰 판단한다.
- 보조 지표, 종가, 브로커 응답 중 하나라도 신뢰할 수 없으면 자동 실행을 강행하지 않는다.

## 6. 실행 가드

실거래 실행은 운영 모드와 보호 장치를 함께 통과해야 한다.

### 공통 가드

- `DB 기반 Kill Switch = OFF`
- 미정리 주문이 없을 것
- 브로커 및 핵심 시세 연동이 정상일 것
- 시장 상태가 `정규장` 또는 명시적으로 승인된 예외 상태일 것

### 모드별 가드

- `PAPER`
  - `trading.execution.enabled` 값과 무관하게 실주문을 금지한다.
- `MANUAL_LIVE`
  - 수동 API로만 실주문을 허용한다.
  - 스케줄 기반 자동 실주문은 허용하지 않는다.
- `AUTO_LIVE`
  - `trading.execution.enabled = true`
  - 스케줄러 활성화 상태
  - 캘린더와 데이터 정상성 조건까지 모두 충족해야 한다.

운영 원칙:

- 스케줄러가 켜져 있어도 운영 모드가 `AUTO_LIVE`가 아니면 자동 주문은 수행하지 않는다.
- Kill Switch가 ON이면 자동 실행과 수동 실행 모두 허용하지 않는 것을 원칙으로 한다.
- 데이터 미확정, 브로커 장애, 일정 불확실성 상태에서는 `AUTO_LIVE`를 유지하지 않는다.

### AUTO_LIVE 재개 절차

`AUTO_LIVE` 재개 조건은 승격 조건과 동일하게 관리한다. 아래 조건을 모두 통과하기 전에는 자동 실행을 다시 허용하지 않는다.

- 최근 5영업일 연속 EOD 계산 성공
- 미정리 주문 `0건`
- 중복 Job 정황 `0건`
- 브로커 및 핵심 시세 연동 정상
- 시장 상태가 `정규장` 또는 명시 승인된 예외 상태
- 운영자 1인의 재개 승인 기록 존재

## 7. 운영 API

### 수동 운영 API

| API | 용도 |
| :--- | :--- |
| `GET /api/operations/mode` | 현재 운영 모드와 최근 운영 감사 이력 요약 조회 |
| `POST /api/operations/mode` | 운영 모드 수동 전환 및 승인 기록 생성 |
| `GET /api/operations/mode-history` | 운영 모드 감사 로그 최신순 조회 |
| `POST /api/jobs/manual-rebalance` | 현재 시점 기준 리밸런싱 전체 실행 |
| `POST /api/jobs/eod-calculation` | EOD 계산 수동 실행 |
| `POST /api/jobs/{jobId}/execute` | 특정 Job 재실행 |
| `POST /api/jobs/{jobId}/orders/{orderId}/confirm` | `CONFIRMATION_REQUIRED` 주문의 브로커 접수 여부 수동 확인 |
| `GET /api/dashboard/summary` | 포트폴리오, 전략 상태, 보조 지표, 최근 Job 요약 조회 |
| `GET /api/dashboard/history` | Job 이력, 운영 모드 감사 이력, 최근 성과 스냅샷 조회 |
| `GET /api/dashboard/performance` | 성과 요약, 일별 NAV/DD 시계열, 월별 손익 조회 |

### KIS 연동 확인용 API

| API | 용도 |
| :--- | :--- |
| `GET /kis/overseas-balance` | 해외 잔고 조회 |
| `GET /kis/quoted-price` | 종목 시세 조회 |
| `GET /kis/realtime/overseas` | 실시간 시세 구독 |

KIS 토큰/승인키 직접 반환 API와 직접 주문 테스트 API는 운영 안전을 위해 제공하지 않는다.
주문은 `POST /api/jobs/manual-rebalance` 또는 `POST /api/jobs/{jobId}/execute` 경로를 통해 실행 가드와 리스크 한도를 거쳐야 한다.
운영 실주문은 `ExecutionJobExecutor -> GuardedOrderBroker -> KisOrderBroker` 경로만 사용한다.

### KIS 개발 진단 API

아래 API는 `dev` 또는 `local` profile에서만 bean이 등록된다. `prod` profile에서는 등록되지 않아야 하며, 운영 직접 주문 API로 사용해서는 안 된다.

| API | 용도 |
| :--- | :--- |
| `POST /kis/dev-diagnostics/orders/payload` | KIS 해외주식 주문 request body와 `tr_id`를 dry-run으로 생성 |
| `GET /kis/dev-diagnostics/token` | KIS access token cache 상태 조회 |
| `POST /kis/dev-diagnostics/token/refresh` | KIS access token 발급/사용 가능 여부 확인 |
| `POST /kis/dev-diagnostics/approval-key/refresh` | WebSocket approval key 발급 가능 여부 확인 |

진단 API 운영 원칙:

- 모든 진단 API도 `X-API-KEY` 인증과 private rate limit을 통과해야 한다.
- `orders/payload`는 `willExecute=false` 응답만 제공하며 KIS 주문 API를 호출하지 않는다.
- access token과 approval key 원문은 반환하지 않고, 만료 시각, TTL, 길이, fingerprint 같은 진단용 메타데이터만 반환한다.
- `/kis/overseas/order/...` 형태의 직접 주문 API는 제공하지 않는다.

로컬 사용 방법:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:TRADING_API_KEY='local-dev-key'
$env:KIS_APP_KEY='...'
$env:KIS_APP_SECRET='...'
$env:KIS_ACCOUNT_NO='...'
$env:KIS_CANO='...'
$env:KIS_ACNT_PRDT_CD='...'
./gradlew.bat bootRun
```

기본 로컬 실행인 `./gradlew.bat bootRun`만으로는 `local` profile이 자동 활성화되지 않는다. 진단 API를 사용하려면 `SPRING_PROFILES_ACTIVE=local` 또는 `--spring.profiles.active=local`을 명시해야 한다.

외부 KIS 호출 여부:

| API | 외부 KIS 호출 | 주문 실행 |
| :--- | :--- | :--- |
| `POST /kis/dev-diagnostics/orders/payload` | 없음 | 없음 |
| `GET /kis/dev-diagnostics/token` | 없음 | 없음 |
| `POST /kis/dev-diagnostics/token/refresh` | 있음 | 없음 |
| `POST /kis/dev-diagnostics/approval-key/refresh` | 있음 | 없음 |

호출 예시:

```powershell
$apiKey='local-dev-key'

curl.exe -s `
  -H "X-API-KEY: $apiKey" `
  http://127.0.0.1:8080/kis/dev-diagnostics/token

curl.exe -s `
  -X POST `
  -H "X-API-KEY: $apiKey" `
  http://127.0.0.1:8080/kis/dev-diagnostics/token/refresh

curl.exe -s `
  -X POST `
  -H "X-API-KEY: $apiKey" `
  http://127.0.0.1:8080/kis/dev-diagnostics/approval-key/refresh

curl.exe -s `
  -X POST `
  -H "X-API-KEY: $apiKey" `
  -H "Content-Type: application/json" `
  -d '{"symbol":"QQQ","side":"BUY","quantity":1,"limitPrice":421.12}' `
  http://127.0.0.1:8080/kis/dev-diagnostics/orders/payload
```

## 8. 보안 정책

### 제품 기준 운영 정책

- 기본 운영 모델은 `사설망 전용`이다.
- 대시보드, 운영 API, Actuator는 로컬, 사설망, VPN, 또는 그에 준하는 내부 경로 뒤에 두는 것을 기본으로 한다.
- 외부 인터넷 직접 노출은 기본 운영 형태가 아니며, 필요한 경우 별도 인증 또는 프록시 보호 계층을 추가해야 한다.

### 현재 구현

- `RateLimitFilter`와 `ApiKeyAuthFilter`는 `/*` 전체 경로에 적용된다.
- 기본값은 공개 경로 없음이다.
- 공개 예외는 `trading.security.public-path-prefixes`로만 열 수 있다.
- 공개 예외를 하나라도 열면 `trading.security.public-path-protection-mode`와 `trading.security.public-path-protection-note`를 함께 설정해야 한다.
- 공개 읽기 API의 CORS 허용 origin은 `trading.security.public-read-allowed-origins`로 제한한다.
- 공개 경로도 별도 public rate limit을 적용하며, 무제한 예외로 두지 않는다.
- 보호 모드는 `PRIVATE_NETWORK`, `VPN`, `REVERSE_PROXY`, `ADDITIONAL_AUTH` 중 하나를 사용한다.
- `REVERSE_PROXY` 모드에서는 `trading.security.public-trusted-proxy-ranges`를 반드시 설정한다.
- 공개 경로의 client IP는 신뢰 프록시 범위 안에서만 `trading.security.public-client-ip-header`를 사용해 해석한다.
- `trading.security.public-access-log-enabled=true`면 공개 읽기 API 접근 로그가 남는다.
- `trading.security.api-key`는 필수 설정이며, 값이 없거나 공백이면 애플리케이션이 기동하지 않는다.
- `prod` 프로필에서는 아래 경로를 공개 예외로 둘 수 없고, 설정하면 애플리케이션이 기동하지 않는다.
  - `/api/dashboard/**`
  - `/api/jobs/**`
  - `/execution/**`
  - `/kis/**`
  - `/actuator/**`
- 그 외 경로는 `X-API-KEY` 헤더 검증이 필요하다.
- 비공개 경로는 IP 기준 초당 10 요청으로 제한된다.

### 공개 포트폴리오 읽기 API 운영 원칙

- 공개 API는 `/public/api/v1/**`만 허용한다.
- 공개 API는 운영 대시보드(`/api/dashboard/**`)를 재사용하지 않는다.
- 공개 데이터는 최신 EOD 스냅샷 기준이며 장중 잔고/실시간 값은 사용하지 않는다.
- 공개 응답은 비율 중심이다. NAV, 현금 금액, 주문 이력, 운영 감사 이력은 포함하지 않는다.
- 공개 페이지는 별도 외부 프론트가 소비하고, 애플리케이션은 JSON API만 제공한다.
- 운영 환경에서는 `REVERSE_PROXY` 선언을 기본값으로 사용하고, reverse proxy 또는 CDN 뒤에서만 노출한다.
- 공개 API 응답은 짧은 cache-control을 두고, 외부 캐시는 proxy/CDN 계층에서 관리한다.
- reverse proxy 또는 CDN egress CIDR은 `trading.security.public-trusted-proxy-ranges`에 실제 값으로 반영한다.
- 브라우저 소비 origin만 `trading.security.public-read-allowed-origins`에 명시하고 wildcard는 사용하지 않는다.
- 운영 환경에서는 `TRADING_PUBLIC_READ_ALLOWED_ORIGINS`, `TRADING_PUBLIC_TRUSTED_PROXY_RANGES` 환경변수로 실제 목록을 주입한다.
- prod에서는 `example.com` 계열 샘플 origin, wildcard origin, path가 포함된 origin, `10.0.0.0/8` 샘플 CIDR을 두면 기동 실패로 막는다.
- 접근 로그는 `my.side.trading.publicapi.access` logger 기준으로 수집하고, reverse proxy access log와 함께 본다.

### 현재 구현과 기준 정책 간 차이
- 현재 구현은 일부 조회 경로를 공개 예외로 취급한다.
- 운영 기준으로는 대시보드와 Actuator를 기본 공개 경로로 보지 않는다.
- 운영 환경에서는 리버스 프록시, 사설망, 별도 인증 계층 중 최소 하나를 추가하는 것이 필수에 가깝다.

## 9. 장애 및 예외 시나리오

### KIS 장애

1. 주문 API 또는 잔고 조회가 실패하면 자동 실행 결과를 먼저 확인한다.
2. 장애가 일시적이지 않다고 판단되면 `AUTO_LIVE`를 해제하고 `MANUAL_LIVE` 또는 `PAPER`로 낮춘다.
3. 필요하면 `trading.execution.enabled`를 비활성화하거나 Kill Switch를 켠다.
4. 복구 전까지는 수동 주문 실행도 보수적으로 제한한다.

KIS HTTP timeout은 `kis` 설정에서 관리한다.

- 연결 timeout: `kis.connect-timeout` (`KIS_CONNECT_TIMEOUT`)
- 요청 대기 timeout: `kis.request-timeout` (`KIS_REQUEST_TIMEOUT`)

주문성 API 장애는 조회성 API 장애보다 보수적으로 다룬다.

- `/uapi/overseas-stock/v1/trading/order`, `/uapi/overseas-stock/v1/trading/order-rvsecncl`에는 자동 HTTP retry를 적용하지 않는다.
- 주문 요청 후 timeout 또는 network error가 발생하면 주문 미접수로 단정하지 않는다.
- `brokerOrderId`가 있으면 `inquire-ccnl`에서 해당 주문번호를 확인한다.
- `brokerOrderId`가 없으면 신규 자동 주문을 멈추고 `inquire-nccs`, `inquire-ccnl`에서 같은 symbol/side와 주문 시각 근처 내역을 확인한다.
- 주문 접수 여부가 불명확하면 `AUTO_LIVE`를 유지하지 말고 수동 정리 계획을 세운다.

`CONFIRMATION_REQUIRED` 주문은 아래 절차로 확인한다.

1. 먼저 신규 자동 주문을 중지하고, `AUTO_LIVE`를 유지하지 말아야 할 상황인지 확인한다.
2. 필요하면 `POST /api/operations/mode`로 `MANUAL_LIVE` 또는 `PAPER`로 낮춘 뒤 확인 작업을 진행한다.
3. 대시보드 또는 Job 이력에서 `jobId`, `orderId`, `symbol`, `side`, `quantity`, `brokerOrderId`를 확인한다.
4. `POST /api/jobs/{jobId}/orders/{orderId}/confirm`을 호출한다.
5. 응답을 기준으로 주문 상태와 후속 조치를 기록한다.

호출 조건:

- `X-API-KEY` 인증이 필요하다.
- 요청 body는 사용하지 않는다.
- `CONFIRMATION_REQUIRED` 상태 주문에만 사용할 수 있다.
- 직접 KIS 주문 API를 재호출하거나 동일 주문을 재전송하는 절차가 아니다.

응답 해석:

| `data.inquiryStatus` | 주문 상태 변화 | 운영 조치 |
| :--- | :--- | :--- |
| `FOUND` | 해당 주문이 `ACCEPTED`로 해소된다. | `data.brokerOrderId`와 브로커 화면/체결 내역을 대조한 뒤 미정리 주문 여부를 다시 확인한다. |
| `NOT_FOUND` | `CONFIRMATION_REQUIRED` 상태를 유지한다. | 주문 미접수로 단정하지 말고 브로커 화면, KIS 조회, 계좌 체결 내역을 추가 확인한다. |
| `INQUIRY_FAILED` | `CONFIRMATION_REQUIRED` 상태를 유지한다. | KIS 조회 장애로 보고 운영 모드를 보수적으로 낮춘 상태에서 재시도 또는 수동 확인한다. |

현재 구현 기준:

- `brokerOrderId`가 있으면 `inquire-ccnl`에서 주문번호 exact match를 우선 확인한다.
- `brokerOrderId`가 없으면 `inquire-nccs`, `inquire-ccnl`에서 symbol/side/quantity가 같은 후보를 찾는다.
- 주문 요청 시각 기준 window 매칭과 조회 API pagination(`CTX_AREA_*`) 처리는 아직 별도 v2 작업이다.
- `NOT_FOUND` 또는 `INQUIRY_FAILED` 결과는 자동 `REJECTED` 처리 사유가 아니다.

호출 예시:

```powershell
$apiKey='local-dev-key'

curl.exe -s `
  -X POST `
  -H "X-API-KEY: $apiKey" `
  http://127.0.0.1:8080/api/jobs/7/orders/3/confirm
```

KIS 장애 로그와 알림은 추적 가능한 최소 필드만 남긴다.

- 권장 필드: `symbol`, `side`, `tr_id`, `failureKind`, `failureCode`, `brokerOrderId`
- 금지 필드: access token, app secret, app key, 계좌번호 원문, approval key, AES key/iv
- KIS 응답 body를 남겨야 할 때는 계좌 식별자와 인증 관련 값 포함 여부를 먼저 확인하고 마스킹한다.
- 상세 정책은 `docs/decisions/004_kis_http_policy.md`를 기준으로 한다.

### Yahoo 데이터 결측

1. VIX 또는 200MA 조회 실패 여부를 로그에서 확인한다.
2. 보조 지표가 비어 있는 상태에서 전략 판단 결과가 왜곡될 수 있는지 점검한다.
3. 필요하면 EOD 계산을 재실행하고, 반복되면 `PAPER` 또는 `MANUAL_LIVE`로 전환한다.

Yahoo HTTP timeout은 `trading.fx.yahoo` 설정에서 관리한다.

- 연결 timeout: `trading.fx.yahoo.connect-timeout` (`TRADING_FX_YAHOO_CONNECT_TIMEOUT`)
- 요청 대기 timeout: `trading.fx.yahoo.request-timeout` (`TRADING_FX_YAHOO_REQUEST_TIMEOUT`)

### 부분 체결 지속

1. 최근 Job과 주문 상태를 조회한다.
2. 부분 체결 후 남은 수량이 반복적으로 남는지 확인한다.
3. 재시도 정책이 기대대로 동작하지 않으면 자동 실행을 중단하고 수동 점검한다.

### 중복 실행 방지

1. 동일 `signalDate`의 Job이 이미 생성되었는지 확인한다.
2. 중복 Job 생성이 차단되었는지 로그와 이력을 통해 확인한다.
3. 중복 실행 정황이 있으면 즉시 운영 이슈로 취급한다.

### Kill Switch 활성화

1. Kill Switch ON 여부를 확인한다.
2. 자동 리밸런싱 스케줄은 실행을 건너뛰어야 한다.
3. 원인 해소 전에는 수동 실행도 허용하지 않는 것을 원칙으로 한다.

### 자동 실행 재개 조건

자동 실행을 다시 `AUTO_LIVE`로 올리기 전에는 아래 조건을 모두 확인한다.

- 최근 5영업일 연속 EOD 계산이 성공했을 것
- 브로커 및 핵심 시세 연동 복구가 확인되었을 것
- 미정리 주문, 미체결 잔량, 중복 Job 정황이 `0건`일 것
- 시장 상태가 `정규장` 또는 명시적으로 승인된 예외 상태일 것
- 운영자 1인의 재개 승인 기록이 남아 있을 것

## 10. 운영 체크리스트

### 장 시작 전/후 확인 항목

- 현재 운영 모드(`PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`)
- 스케줄러 활성화 여부
- 실행 플래그 상태
- Kill Switch 상태
- 전일 EOD 계산 성공 여부
- KIS 인증/잔고 조회 정상 여부
- 미국장 일정(DST, 휴장, 조기폐장) 이상 여부

### 수동 실행 전 확인 항목

- 최신 전략 상태 저장 여부
- 대시보드 요약 데이터의 이상 여부
- 외부 시세 데이터 정상 여부
- 당일 동일 Job 존재 여부
- 부분 체결 또는 미정리 주문 존재 여부
- 현재 운영 모드가 수동 실주문을 허용하는지 여부

### 승격 전 체크리스트

- 최근 5영업일 EOD 성공 여부
- 미정리 주문 `0건` 여부
- 중복 Job 정황 `0건` 여부
- 브로커 및 핵심 시세 연동 정상 여부
- 시장 상태가 `정규장` 또는 승인된 예외 상태인지 여부
- 운영자 승인 기록 작성 여부

### 강등 후 체크리스트

- 자동 실행이 즉시 중지되었는지 여부
- 현재 운영 모드가 `MANUAL_LIVE` 또는 `PAPER`로 변경되었는지 여부
- 미정리 주문 및 부분 체결 정리 계획 수립 여부
- 장애 원인이 외부 연동인지 내부 정책/구현인지 분리되었는지 여부
- 재개 전 추가 검증 기간 또는 점검 항목이 기록되었는지 여부

### 장애 발생 시 우선 조치

- 먼저 실행 차단 여부를 결정한다.
- 자동 실행을 멈춘 뒤 원인을 확인한다.
- 원인이 외부 연동인지 내부 정책 불일치인지 분리한다.
- 필요하면 EOD 계산과 Job 이력을 다시 확인한다.
- 복구 전까지 운영 모드를 보수적으로 낮춘다.

## 11. 로그와 모니터링 포인트

- 애플리케이션 로그와 에러 로그는 파일 롤링으로 기록된다.
- Actuator `health`, `info`, `metrics`가 노출된다.
- 주문 생성, 주문 요청, 체결 결과, 취소, 재시도는 우선 확인해야 할 핵심 로그다.
- 보안 관점에서는 잘못된 API Key 접근과 레이트 리밋 초과 로그를 함께 본다.
- 운영 KPI와 안전 KPI 산출에 필요한 이벤트는 운영 감사 로그와 알림 이벤트 기준으로 추적한다.
- KPI breach 발생 시점, 초과 항목, 당시 운영 모드는 반드시 남겨야 한다.
- `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE` 전환 이벤트와 승인 기록은 현재 `operation_mode_audit`에 남는다.

## 12. 최소 장애 알림

최소 장애 알림은 현재 구현 범위다. `AUTO_LIVE` 운영 완성도를 위해 아래 이벤트를 dedupe 가능한 운영 알림으로 발행한다.

- 알림 이벤트 모델은 `OpsAlert`이고, 발행 포트는 `OpsAlertPublisher`다.
- 퍼블리셔 파이프라인은 `DeduplicatingOpsAlertPublisher -> CompositeOpsAlertPublisher -> channel publisher` 구조다.
- 로그 채널은 `WARN`, `ERROR`를 모두 기록한다.
- Discord 채널은 `trading.operation.alerts.discord.min-severity` 이상만 전송하고, 기본값은 `ERROR`다.
- Discord HTTP timeout은 `trading.operation.alerts.discord.connect-timeout` (`TRADING_DISCORD_CONNECT_TIMEOUT`)과 `trading.operation.alerts.discord.request-timeout` (`TRADING_DISCORD_REQUEST_TIMEOUT`)으로 관리한다.
- dedupe는 `dedupeKey + TTL` 기준으로 동작한다.
- 알림 전송 실패는 EOD / 주문 / execution guard 본 흐름을 실패시키지 않는다.

현재 우선 알림 이벤트:

- EOD 계산 실패
- 브로커 장애 또는 주문 API 실패
- 데이터 결측 또는 `DATA_UNCERTAIN`
- 미정리 주문 또는 부분체결 지속
- Kill Switch 활성화
- 중복 Job 탐지
- KPI breach
- 실행 리스크 한도 breach

알림 채널은 특정 제품에 고정하지 않고, 운영자가 비동기로 즉시 인지 가능한 채널을 사용한다. 현재 구현 채널은 로그와 Discord Incoming Webhook이다.

## 13. 운영 문서 사용 원칙

- 운영 절차와 운영 모드 기준은 이 문서를 기준으로 본다.
- 제품 문서에는 운영 원칙과 범위만 두고, 승격/강등 체크리스트와 예외 절차의 최신 기준은 이 문서를 단일 운영 기준으로 관리한다.
- 전략 숫자와 해석이 필요하면 `docs/STRATEGY_SPEC.md`를 함께 본다.
- 현재 코드와 운영 기대가 다를 때는 `docs/CURRENT_IMPLEMENTATION_SYNC.md`에서 갭을 확인한다.
- 보안 필터 범위와 공개 경로 정책의 기술적 근거는 `docs/decisions/001_code_review_security_and_refactoring.md`를 참조한다.

