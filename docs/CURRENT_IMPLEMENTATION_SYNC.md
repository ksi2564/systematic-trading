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

이 문서는 현재 코드가 실제로 무엇을 구현하고 있는지와, 목표 정책이 무엇인지 사이의 차이를 관리하기 위한 기준 문서다. 기획 문서와 구현 문서가 분리된 이후, 구현팀 handoff와 후속 우선순위 정리에 사용한다.

## 2. 현재 구현 요약

현재 구현은 다음 범위를 포함한다.

- QQQ 종가 기반 전략 상태 계산
- DD / phase / target weights 계산
- Circuit Breaker 판단
- 리밸런싱 판단과 주문 인텐트 생성
- 주문 생성과 Job 단위 저장
- 주문 실행, 부분 체결 확인, 취소, 재시도
- 자동 스케줄 기반 EOD 계산과 자동 리밸런싱
- 대시보드 요약 및 Job 이력 조회
- API Key 인증과 레이트 리밋

핵심 기준 소스:

- 전략 비중: `WeightSet`
- 전략 판단: `RebalanceDecisionService`
- Circuit Breaker: `CircuitBreakerService`
- 주문 생성: `ExecutionJobCreateService`, `ExecutionOrderFactory`
- 주문 재시도: `RetryableOrderExecutor`
- 보안: `WebFilterConfig`, `ApiKeyAuthFilter`, `RateLimitFilter`
- 운영 API: `ExecutionJobController`, `DashboardController`

## 3. 구현 완료 항목

- `trading.strategy.tolerance-pct` 기본값 5.0
- 운용 대상 기본 심볼 `QQQ`, `QLD`, `TQQQ`
- 매도 우선순위 `TQQQ -> QLD -> QQQ`
- 매수 우선순위 `QQQ -> QLD -> TQQQ`
- EOD 스케줄 `06:15 KST`, 화요일~토요일
- 자동 리밸런싱 스케줄 `23:45 KST`, 월요일~금요일
- 실행 가드 `trading.execution.enabled`, Kill Switch
- 필터 적용 범위 `/*`
- 공개 예외 경로 대시보드/Swagger/API Docs/Actuator
- 비공개 경로 레이트 리밋 IP 기준 초당 10 요청

## 4. 정책-구현 불일치 항목

아래 항목은 현재 코드와 목표 정책이 명확히 어긋나는 영역이다.

- 운영 모드 3단계(`PAPER`, `MANUAL_LIVE`, `AUTO_LIVE`) 부재
- 운영 모드 승격/강등 정량 게이트 부재
- 대시보드 공개 범위와 사설망 전용 운영 기준의 불일치
- 최근 체결가 + 0.3% 버퍼 기반 가격 정책
- 재시도 시 주문 가격 미갱신
- 시장 캘린더, DST, 휴장/조기폐장 인지 부재
- VIX Circuit Breaker의 실질적 반영 부족
- KPI 수집 및 대시보드화 부재
- KPI breach 기반 강등 로직 부재
- 실행 리스크 한도 모델 부재
- 최소 장애 알림 부재
- 파라미터 변경 레지스터 부재
- 비용/성과 해석 기준의 문서-구현 연결 부족

## 5. 우선순위별 갭 목록

### P1. 운영 모드 / 실행 가드 정책 부재

- 영역: 운영 / 실행 제어
- 현재 구현:
  - 실주문 가드는 `trading.execution.enabled`와 Kill Switch 중심이다.
  - `PAPER`, `MANUAL_LIVE`, `AUTO_LIVE` 같은 명시적 운영 모드는 없다.
- 문서 기준:
  - `docs/PRODUCT_REQUIREMENTS.md`: 운영 모델과 승인 원칙
  - `docs/OPERATIONS_RUNBOOK.md`: 운영 모드별 허용 행위, 실행 가드
- 영향:
  - 수동 승인 실거래와 자동 실거래를 구분해 제어하기 어렵다.
  - 장애 복구 직후나 데이터 불확실성 상황에서 모드 전환 정책이 코드와 연결되지 않는다.
  - `AUTO_LIVE` 승격 조건과 강등 트리거가 정량 게이트로 구현돼 있지 않다.
- 권장 조치:
  - 운영 모드를 명시적 설정 또는 상태 모델로 도입한다.
  - 수동 API와 스케줄 실행의 허용 범위를 모드별로 분리한다.
  - 최근 5영업일 EOD 성공, 미정리 주문 `0건`, 중복 Job 정황 `0건` 등 승격/재개 정량 게이트를 코드와 연결한다.

### P1. 보안 공개 경로

- 영역: 보안 / 운영
- 현재 구현:
  - `WebFilterConfig`는 `RateLimitFilter`, `ApiKeyAuthFilter`를 `/*`에 적용한다.
  - `ApiKeyAuthFilter`와 `RateLimitFilter`는 `/api/dashboard`, `/swagger-ui`, `/v3/api-docs`, `/actuator`를 공개 경로로 예외 처리한다.
- 문서 기준:
  - `docs/PRODUCT_REQUIREMENTS.md`: 사설망 전용 기본 운영 모델
  - `docs/OPERATIONS_RUNBOOK.md`: 보안 정책
- 영향:
  - 운영 상태와 최근 작업 이력이 인증 없이 노출될 수 있다.
  - 현재 구현은 사설망 전용 기본 정책과 정면으로 어긋난다.
- 권장 조치:
  - 대시보드와 Actuator의 공개 정책을 환경별로 분리한다.
  - 최소한 운영 환경에서는 프록시 또는 추가 인증 계층으로 보호한다.

### P1. 가격 정책

- 영역: 주문 정책
- 현재 구현:
  - `ExecutionJobCreateService`는 기본 버퍼 0.3%를 사용한다.
  - `ExecutionOrderFactory`는 `RealtimePriceProvider.getLastPrice()`를 기준으로 주문 가격을 계산한다.
  - `PricingConfig`에는 매수/매도 버퍼 0.5%, 수수료 0.25%가 정의돼 있지만, 실제 주문 생성의 기본 버퍼와 일치하지 않는다.
- 문서 기준:
  - `docs/STRATEGY_SPEC.md`: 주문 가격 정책
- 영향:
  - 체결 가능성을 높인다는 명목으로 불필요한 슬리피지가 발생할 수 있다.
  - 문서와 구현의 가격 정책이 달라 운영자가 실제 동작을 오해할 수 있다.
- 권장 조치:
  - 호가 기반 가격 공급 인터페이스를 추가한다.
  - 주문 생성의 기본 버퍼 정책을 설정 기반으로 정리한다.
  - `PricingConfig`와 `ExecutionJobCreateService`의 기준을 일치시킨다.

### P1. 재시도 로직

- 영역: 주문 실행
- 현재 구현:
  - `RetryableOrderExecutor`는 최대 3회 재시도와 10초 대기 시간을 사용한다.
  - 버퍼 시퀀스는 0.3% -> 0.5% -> 0.8%다.
  - 하지만 재시도 주문 생성 시 수량만 변경하고, 기존 `refPrice`와 `limitPrice`는 그대로 유지한다.
- 문서 기준:
  - `docs/STRATEGY_SPEC.md`: 재시도 정책
  - `docs/OPERATIONS_RUNBOOK.md`: 장애 및 예외 시나리오
- 영향:
  - “재시도 시 가격 재조정”이 실제로는 수행되지 않는다.
  - 대기 시간이 길고 체결 전략이 둔감해 시장 대응력이 떨어질 수 있다.
- 권장 조치:
  - 재시도 시점마다 시세를 다시 조회하는 흐름으로 변경한다.
  - `ExecutionOrder` 재생성 시 가격도 함께 갱신한다.
  - 버퍼 로직을 틱 단위 보정 기준으로 재정의한다.

### P2. 시장 캘린더 정책 부재

- 영역: 운영 스케줄 / 일정 인지
- 현재 구현:
  - EOD와 자동 리밸런싱은 고정 KST 시각 스케줄에 의존한다.
  - 휴장, 조기폐장, DST 변동, 데이터 미확정 상태를 별도 인지하지 않는다.
- 문서 기준:
  - `docs/OPERATIONS_RUNBOOK.md`: 스케줄 기준, 미국장 캘린더 운영 정책
- 영향:
  - 시장 일정 예외일에 자동 실행이 잘못 동작할 수 있다.
  - 운영자가 문서상 요구되는 캘린더 기반 제어를 코드에서 보장받지 못한다.
- 권장 조치:
  - 시장 상태(`정규장`, `휴장`, `조기폐장`, `데이터 미확정`)를 식별하는 정책 레이어를 추가한다.
  - 일정 예외일에는 자동 실행을 skip하거나 수동 모드로 강등한다.

### P2. Circuit Breaker의 VIX 반영

- 영역: 전략 판단
- 현재 구현:
  - `RebalanceDecisionService`는 `CircuitBreakerService.adjustWeights()`를 호출한다.
  - VIX 트리거는 계산하지만 `prevWeights`에 `null`을 전달한다.
  - 따라서 “이전보다 공격적 확대 제한” 로직은 실제로 동작하지 않는다.
- 문서 기준:
  - `docs/STRATEGY_SPEC.md`: Circuit Breaker 정책
- 영향:
  - 문서상 존재하는 VIX 방어 규칙이 실동작에서는 약화된다.
- 권장 조치:
  - 이전 비중 전달 방식 또는 상태 저장 구조를 보강한다.
  - VIX 제약을 현재 상태 기준으로 재정의해 null 의존을 제거한다.

### P2. KPI / 성과 측정 부재

- 영역: 리포팅 / 운영
- 현재 구현:
  - `DashboardController`는 포트폴리오, 전략 상태, Circuit Breaker 정보, 최근 Job만 제공한다.
  - 운영 KPI, 안전 KPI, 체결 KPI, 성과 KPI를 산출할 데이터 모델이 없다.
- 문서 기준:
  - `docs/PRODUCT_REQUIREMENTS.md`: 성공 기준
  - `docs/OPERATIONS_RUNBOOK.md`: 로그와 모니터링 포인트
- 영향:
  - 운영 품질은 일부 확인 가능하지만 KPI 기준으로 관리할 수 없다.
  - 전략 성과와 안전 기준을 객관적으로 평가하기 어렵다.
  - KPI breach가 발생해도 자동 강등 또는 운영 액션으로 연결되지 않는다.
- 권장 조치:
  - KPI 산출에 필요한 이벤트와 일자별 스냅샷 모델을 설계한다.
  - 대시보드에 KPI 또는 KPI 산출용 조회 모델을 추가한다.
  - KPI breach 발생 시 운영 모드 강등 또는 자동 실행 중지 정책을 연결한다.

### P2. 실행 리스크 한도 모델 부재

- 영역: 주문 실행 / 운영 제어
- 현재 구현:
  - 1회 최대 주문 금액, 1일 최대 회전율, 재시도 총 노출 한도, 허용 슬리피지 상한을 표현하는 모델이 없다.
  - 주문 생성과 재시도는 개별 가격/수량 로직 중심으로 동작한다.
- 문서 기준:
  - `docs/STRATEGY_SPEC.md`: 실행 리스크 한도
- 영향:
  - 전략 논리와 별개로 과대 주문, 과잉 회전, 재시도 누적 노출을 제어하기 어렵다.
- 권장 조치:
  - 실행 리스크 한도 4종을 설정 또는 정책 모델로 도입한다.
  - 한도 초과 시 자동 실행 중지 또는 `MANUAL_LIVE` 강등을 연결한다.

### P2. 최소 장애 알림 부재

- 영역: 운영 관측성
- 현재 구현:
  - EOD 실패, 브로커 장애, 데이터 결측, 미정리 주문, Kill Switch 등의 최소 장애 알림 체계가 없다.
- 문서 기준:
  - `docs/PRODUCT_REQUIREMENTS.md`: 다음 범위
  - `docs/OPERATIONS_RUNBOOK.md`: 최소 장애 알림
- 영향:
  - `AUTO_LIVE` 운영 중 핵심 장애를 운영자가 즉시 인지하기 어렵다.
- 권장 조치:
  - 최소 장애 알림 이벤트 5종을 우선 정의한다.
  - 채널은 구현 시점에 선택하되, 비동기 즉시 통지 기준은 공통으로 유지한다.

### P2. 비용 / 성과 해석 기준 연결 부족

- 영역: 전략 문서화 / 성과 해석
- 현재 구현:
  - 실주문 계산에는 일부 수수료만 반영된다.
  - 환율, 세금, 장기 보유 비용, 성과 해석 기준이 코드와 연결되지 않는다.
- 문서 기준:
  - `docs/STRATEGY_SPEC.md`: 비용 가정, 전략 리스크와 한계
  - `docs/PRODUCT_REQUIREMENTS.md`: 주요 리스크와 제약
- 영향:
  - 문서상 성과 해석 기준과 실제 시스템 계산 범위가 분리돼 있다.
  - 운영자는 “실주문 판단 비용”과 “성과 해석 비용”을 별도로 이해해야 한다.
- 권장 조치:
  - 주문 계산에 반영하는 비용과 성과 해석에 반영하는 비용을 분리해 모델링한다.
  - KPI/리포팅 설계 시 환율·세금·장기보유비용의 반영 범위를 명시한다.

### P3. 파라미터 변경 레지스터 부재

- 영역: 전략 운영 관리
- 현재 구현:
  - 핵심 파라미터 변경 이력, 변경일, 변경자, 검증 근거를 함께 관리하는 구조가 없다.
- 문서 기준:
  - `docs/STRATEGY_SPEC.md`: 파라미터 변경 레지스터
- 영향:
  - 전략 값 변경이 로그와 코드 변경 내역에 흩어져 남아 추적성이 떨어진다.
- 권장 조치:
  - 핵심 파라미터를 레지스터와 함께 관리하고, 검증 리포트와 연결한다.

### P3. 문서/코드 간 표현 차이

- 영역: 문서 관리
- 현재 구현:
  - 코드와 ADR은 보안 필터가 전체 경로에 적용된다고 명시한다.
  - 과거 단일 기획 문서는 제품 요구사항, 전략 규칙, 운영 절차, 구현 메모를 한 파일에 혼합했다.
- 문서 기준:
  - `docs/PRODUCT_REQUIREMENTS.md`
  - `docs/STRATEGY_SPEC.md`
  - `docs/OPERATIONS_RUNBOOK.md`
  - `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- 영향:
  - 단일 문서 체계에서는 정책 기준과 구현 사실이 쉽게 섞여 해석 오류가 생긴다.
- 권장 조치:
  - 새 4개 문서를 기준 문서로 사용한다.
  - 기존 문서는 archive로 남기고 신규 문서를 우선 참조하도록 안내한다.

## 6. 후속 로드맵 연결

후속 구현은 아래 순서가 합리적이다.

1. 운영 모드 / 실행 가드 정책 도입
2. 보안 공개 범위 재조정
3. 가격 정책과 재시도 로직 정렬
4. 시장 캘린더 정책 도입
5. VIX Circuit Breaker 실동작 보강
6. 실행 리스크 한도 도입
7. KPI 수집, 최소 장애 알림, 성과 측정 모델 추가
8. 파라미터 변경 관리 체계 정리
9. 비용 / 성과 해석 기준의 시스템 연결 정리

로드맵 관리 기준 문서는 `docs/DEVELOPER_ROADMAP.md`다.
