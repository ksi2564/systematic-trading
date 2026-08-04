from datetime import date
from decimal import Decimal
from uuid import uuid4

import pytest

from wallant.broker.base import LiveOrderDisabledError
from wallant.broker.disabled import DisabledBroker
from wallant.domain.execution import (
    IntentStatus,
    OrderSide,
    PlannedOrderIntent,
)


def test_비활성_브로커는_실제주문을_항상_거부한다() -> None:
    intent = PlannedOrderIntent(
        idempotency_key="key",
        account_id=uuid4(),
        strategy_version_id=uuid4(),
        signal_date=date(2026, 1, 1),
        symbol="QQQM",
        side=OrderSide.BUY,
        quantity=1,
        reference_price=Decimal("100"),
        target_weight_pct=Decimal("100"),
        current_weight_pct=Decimal("0"),
        status=IntentStatus.PLANNED,
        reason="테스트",
    )
    with pytest.raises(LiveOrderDisabledError, match="실제 주문을 전송하지 않습니다"):
        DisabledBroker().submit(intent)
