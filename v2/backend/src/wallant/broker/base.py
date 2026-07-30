from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum
from typing import Protocol

from wallant.domain.execution import PlannedOrderIntent
from wallant.domain.portfolio import Portfolio


class BrokerOrderState(StrEnum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"


@dataclass(frozen=True, slots=True)
class BrokerOrderResult:
    state: BrokerOrderState
    broker_order_id: str | None
    message: str


class BrokerPort(Protocol):
    def portfolio(self, account_id: str) -> Portfolio: ...

    def quotes(self, symbols: list[str]) -> dict[str, Decimal]: ...

    def submit(self, intent: PlannedOrderIntent) -> BrokerOrderResult: ...


class LiveOrderDisabledError(RuntimeError):
    pass
