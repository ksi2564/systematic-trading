# 004. KIS HTTP 정책 기준

## Status
Accepted

## Context
KIS REST API는 주문 접수, 주문 취소, 주문 확인용 조회, 계좌/시세/성과 조회, 인증 요청을 함께 제공한다. 이 중 주문 접수와 주문 취소는 외부 브로커 상태를 변경하는 API이므로, 애플리케이션 관점에서 응답을 받지 못했다고 해서 실제 주문이 접수되지 않았다고 단정할 수 없다.

현재 구현은 KIS REST 호출에 `kis.connect-timeout`, `kis.request-timeout`을 적용하고 있으며, `RetryableOrderExecutor`가 체결되지 않은 잔량을 가격 조정 후 다시 주문하는 업무 재시도 정책을 갖고 있다. 이 업무 재시도는 HTTP transport retry와 다르다. 주문성 HTTP 호출에 단순 retry를 추가하면 동일 주문이 중복 접수될 수 있다.

현재 코드에는 정책상 보완해야 할 gap이 있다. `KisOrderBroker`는 `WebClientResponseException`이나 timeout/network 예외를 일반 주문 실패로 반환하고, 상위 실행 흐름은 이를 다음 시도 대상으로 볼 수 있다. 특히 broker order id를 받지 못한 timeout은 접수 여부가 불명확하므로 단순 실패로 처리하면 안 된다.

## Decisions

### 1. KIS REST API를 위험도 기준으로 분류한다
- **주문성 API**: `/uapi/overseas-stock/v1/trading/order`, `/uapi/overseas-stock/v1/trading/order-rvsecncl`
- **주문 확인용 조회 API**: `/uapi/overseas-stock/v1/trading/inquire-nccs`, `/uapi/overseas-stock/v1/trading/inquire-ccnl`
- **일반 조회/인증 API**: 잔고, 현재가, 매수가능금액, 기간손익, access token, approval key

### 2. 주문성 API에는 자동 HTTP retry를 추가하지 않는다
- **결정**: 주문 접수와 주문 취소 POST에는 WebClient/Reactor 수준 retry, 공통 HTTP retry, 자동 backoff retry를 적용하지 않는다.
- **근거**: timeout 또는 network error는 브로커가 요청을 수신했는지 알 수 없는 상태다. 같은 요청을 즉시 재전송하면 중복 주문 또는 중복 취소 요청이 될 수 있다.
- **예외**: KIS가 프로젝트에서 사용할 수 있는 명시적 idempotency key 또는 client order id를 제공하고, 주문 조회로 중복 접수를 완전히 식별할 수 있을 때만 별도 ADR로 재검토한다.

### 3. 접수 여부가 불명확한 실패는 "확인 필요" 상태로 다룬다
- broker order id를 받지 못한 주문 timeout/network error는 미접수 실패로 단정하지 않는다.
- 후속 구현에서는 `KisOrderBroker`가 이런 실패를 일반 실패와 분리해 상위 주문 재시도와 섞이지 않게 해야 한다.
- 확인 필요 상태에서는 신규 자동 주문을 멈추고 `inquire-nccs`, `inquire-ccnl`로 주문 존재 여부를 확인한다.

### 4. 주문 확인은 broker order id와 KIS 조회 API를 기준으로 한다
- broker order id를 받은 주문은 `inquire-ccnl`에서 해당 주문번호를 우선 확인한다.
- broker order id를 받지 못한 주문은 같은 symbol/side, 요청 시각 근처의 `inquire-nccs`, `inquire-ccnl` 결과로 사람 확인을 돕되, 자동으로 동일 주문을 재전송하지 않는다.
- 주문 확인용 조회 API에는 후속 작업에서 bounded retry를 검토할 수 있다. 이 retry는 주문성 API retry와 별도 정책으로 관리한다.

### 5. 로그와 알림은 추적 가능성과 민감정보 보호를 함께 만족해야 한다
- 운영 로그와 `BROKER_API_FAILURE` 알림에는 `symbol`, `side`, `tr_id`, `failureKind`, `failureCode`, `brokerOrderId`를 우선 남긴다.
- access token, app secret, app key, 계좌번호 원문, 승인키, AES key/iv는 로그와 알림에 남기지 않는다.
- KIS 응답 body를 기록해야 할 때는 민감정보 포함 가능성을 먼저 검토하고, 계좌 식별자는 마스킹한다.

## Consequences

- **Positive**: 주문성 API의 중복 주문 리스크를 HTTP retry 개선보다 우선해 차단한다.
- **Positive**: 업무 재시도와 transport retry의 경계를 명확히 해 후속 구현 기준이 선명해진다.
- **Positive**: timeout/network error를 운영자가 확인해야 하는 상태로 다룰 수 있다.
- **Negative**: 일시적 네트워크 장애에서 자동 회복성은 낮게 유지된다.
- **Negative**: 조회 기반 확인 흐름과 운영 알림 필드 보강이 별도 후속 작업으로 필요하다.
