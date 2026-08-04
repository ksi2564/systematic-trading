from __future__ import annotations

from decimal import Decimal

from wallant.broker.base import BrokerOrderResult, LiveOrderDisabledError
from wallant.domain.execution import PlannedOrderIntent
from wallant.domain.portfolio import Portfolio


class DisabledBroker:
    """실주문 호출을 구조적으로 거부하는 v2 MVP 전용 어댑터."""

    def portfolio(self, account_id: str) -> Portfolio:
        raise LiveOrderDisabledError(f"브로커 계좌 조회가 비활성화되어 있습니다: account_id={account_id}")

    def quotes(self, symbols: list[str]) -> dict[str, Decimal]:
        raise LiveOrderDisabledError(
            f"브로커 실시간 시세 조회가 비활성화되어 있습니다: symbols={','.join(symbols)}"
        )

    def submit(self, intent: PlannedOrderIntent) -> BrokerOrderResult:
        raise LiveOrderDisabledError(
            f"v2 MVP는 실제 주문을 전송하지 않습니다. idempotency_key={intent.idempotency_key}"
        )
