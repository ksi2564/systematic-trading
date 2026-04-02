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

- 대시보드 공개 범위와 운영 보안 기대 수준
- VIX Circuit Breaker의 실질적 반영 부족
- 최근 체결가 + 0.3% 버퍼 기반 가격 정책
- 재시도 시 주문 가격 미갱신
- 성과 측정 데이터 부재

## 5. 우선순위별 갭 목록

### P1. 보안 공개 경로

- 영역: 보안 / 운영
- 현재 구현:
  - `WebFilterConfig`는 `RateLimitFilter`, `ApiKeyAuthFilter`를 `/*`에 적용한다.
  - `ApiKeyAuthFilter`와 `RateLimitFilter`는 `/api/dashboard`, `/swagger-ui`, `/v3/api-docs`, `/actuator`를 공개 경로로 예외 처리한다.
- 목표 정책:
  - Swagger와 일부 관리 경로는 예외로 둘 수 있으나, 대시보드와 Actuator는 운영 환경에서 최소한 별도 보호 계층 뒤에 두는 것이 바람직하다.
- 영향:
  - 운영 상태와 최근 작업 이력이 인증 없이 노출될 수 있다.
  - 단일 운영자 환경에서는 편하지만, 외부 노출 환경에서는 위험하다.
- 권장 조치:
  - 대시보드와 Actuator의 공개 정책을 환경별로 분리한다.
  - 최소한 운영 환경에서는 프록시 또는 추가 인증 계층으로 보호한다.
- 관련 코드/문서:
  - `core/adapter/in/web/security/WebFilterConfig.java`
  - `core/adapter/in/web/security/ApiKeyAuthFilter.java`
  - `core/adapter/in/web/security/RateLimitFilter.java`
  - `docs/decisions/001_code_review_security_and_refactoring.md`

### P1. 가격 정책

- 영역: 주문 정책
- 현재 구현:
  - `ExecutionJobCreateService`는 기본 버퍼 0.3%를 사용한다.
  - `ExecutionOrderFactory`는 `RealtimePriceProvider.getLastPrice()`를 기준으로 주문 가격을 계산한다.
  - `PricingConfig`에는 매수/매도 버퍼 0.5%, 수수료 0.25%가 정의돼 있지만, 실제 주문 생성의 기본 버퍼와 일치하지 않는다.
- 목표 정책:
  - 주문 기준 가격은 최근 체결가보다 현재 호가를 우선 사용해야 한다.
  - 기본 버퍼는 0% 또는 1~2틱 수준으로 최소화해야 한다.
  - 설정값과 실제 주문 가격 정책은 일관되어야 한다.
- 영향:
  - 체결 가능성을 높인다는 명목으로 불필요한 슬리피지가 발생할 수 있다.
  - 문서와 구현의 가격 정책이 달라 운영자가 실제 동작을 오해할 수 있다.
- 권장 조치:
  - 호가 기반 가격 공급 인터페이스를 추가한다.
  - 주문 생성의 기본 버퍼 정책을 설정 기반으로 정리한다.
  - `PricingConfig`와 `ExecutionJobCreateService`의 기준을 일치시킨다.
- 관련 코드/문서:
  - `core/application/execution/ExecutionJobCreateService.java`
  - `core/application/execution/ExecutionOrderFactory.java`
  - `adapter/config/PricingConfig.java`
  - `docs/STRATEGY_SPEC.md`

### P1. 재시도 로직

- 영역: 주문 실행
- 현재 구현:
  - `RetryableOrderExecutor`는 최대 3회 재시도와 10초 대기 시간을 사용한다.
  - 버퍼 시퀀스는 0.3% -> 0.5% -> 0.8%다.
  - 하지만 재시도 주문 생성 시 수량만 변경하고, 기존 `refPrice`와 `limitPrice`는 그대로 유지한다.
- 목표 정책:
  - 재시도 직전 현재 호가를 다시 조회해 주문 가격을 갱신해야 한다.
  - 대기 시간은 더 짧고 예측 가능해야 한다.
  - 큰 퍼센트 단위 버퍼 확대는 지양해야 한다.
- 영향:
  - “재시도 시 가격 재조정”이 실제로는 수행되지 않는다.
  - 대기 시간이 길고 체결 전략이 둔감해 시장 대응력이 떨어질 수 있다.
- 권장 조치:
  - 재시도 시점마다 시세를 다시 조회하는 흐름으로 변경한다.
  - `ExecutionOrder` 재생성 시 가격도 함께 갱신한다.
  - 버퍼 로직을 틱 단위 보정 기준으로 재정의한다.
- 관련 코드/문서:
  - `core/application/execution/RetryableOrderExecutor.java`
  - `core/domain/execution/order/ExecutionOrder.java`
  - `docs/STRATEGY_SPEC.md`

### P2. Circuit Breaker의 VIX 반영

- 영역: 전략 판단
- 현재 구현:
  - `RebalanceDecisionService`는 `CircuitBreakerService.adjustWeights()`를 호출한다.
  - VIX 트리거는 계산하지만 `prevWeights`에 `null`을 전달한다.
  - 따라서 “이전보다 공격적 확대 제한” 로직은 실제로 동작하지 않는다.
- 목표 정책:
  - VIX 트리거 시 레버리지 확대 제한이 실제 목표 비중에 반영되어야 한다.
- 영향:
  - 문서상 존재하는 VIX 방어 규칙이 실동작에서는 약화된다.
- 권장 조치:
  - 이전 비중 전달 방식 또는 상태 저장 구조를 보강한다.
  - VIX 제약을 현재 상태 기준으로 재정의해 null 의존을 제거한다.
- 관련 코드/문서:
  - `core/application/execution/RebalanceDecisionService.java`
  - `core/application/strategy/CircuitBreakerService.java`
  - `docs/STRATEGY_SPEC.md`

### P2. 성과 측정 부재

- 영역: 리포팅 / 운영
- 현재 구현:
  - `DashboardController`는 포트폴리오, 전략 상태, Circuit Breaker 정보, 최근 Job만 제공한다.
  - NAV, 월별 PnL, MDD 스냅샷, 성과 추세 데이터는 제공하지 않는다.
- 목표 정책:
  - 운영자는 전략이 잘 작동하는지 최소한의 성과 지표로 확인할 수 있어야 한다.
- 영향:
  - 운영 품질은 확인 가능하지만 전략 성과는 객관적으로 평가하기 어렵다.
- 권장 조치:
  - 대시보드에 성과 스냅샷 모델을 추가한다.
  - 일자별 NAV와 손익 이력을 적재하는 별도 모델을 설계한다.
- 관련 코드/문서:
  - `adapter/in/web/dashboard/DashboardController.java`
  - `adapter/in/web/dashboard/dto/DashboardResponse.java`
  - `docs/PRODUCT_REQUIREMENTS.md`

### P3. 문서/코드 간 표현 차이

- 영역: 문서 관리
- 현재 구현:
  - 코드와 ADR은 보안 필터가 전체 경로에 적용된다고 명시한다.
  - 과거 단일 기획 문서는 제품 요구사항, 전략 규칙, 운영 절차, 구현 메모를 한 파일에 혼합했다.
- 목표 정책:
  - 제품, 전략, 운영, 구현 갭 문서를 분리해 역할이 겹치지 않도록 관리한다.
- 영향:
  - 단일 문서 체계에서는 정책 기준과 구현 사실이 쉽게 섞여 해석 오류가 생긴다.
- 권장 조치:
  - 새 4개 문서를 기준 문서로 사용한다.
  - 기존 문서는 archive로 남기고 신규 문서를 우선 참조하도록 안내한다.
- 관련 코드/문서:
  - `docs/QQQ_AutoTrading_FullPlan.md`
  - `docs/PRODUCT_REQUIREMENTS.md`
  - `docs/STRATEGY_SPEC.md`
  - `docs/OPERATIONS_RUNBOOK.md`

## 6. 후속 로드맵 연결

후속 구현은 아래 순서가 합리적이다.

1. 가격 정책과 재시도 로직 정렬
2. VIX Circuit Breaker 실동작 보강
3. 운영 보안 공개 범위 재조정
4. 성과 측정 모델 추가

로드맵 관리 기준 문서는 `docs/DEVELOPER_ROADMAP.md`다.
