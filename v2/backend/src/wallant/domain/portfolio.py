from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal

from wallant.domain.money import HUNDRED, ZERO, decimal, pct


@dataclass(frozen=True, slots=True)
class Position:
    symbol: str
    quantity: Decimal
    average_price: Decimal = ZERO
    market_price: Decimal = ZERO

    def __post_init__(self) -> None:
        object.__setattr__(self, "symbol", self.symbol.strip().upper())
        object.__setattr__(self, "quantity", decimal(self.quantity))
        object.__setattr__(self, "average_price", decimal(self.average_price))
        object.__setattr__(self, "market_price", decimal(self.market_price))
        if self.quantity < ZERO:
            raise ValueError("공매도 수량은 지원하지 않습니다.")
        if self.market_price < ZERO:
            raise ValueError("시장 가격은 음수일 수 없습니다.")

    @property
    def value(self) -> Decimal:
        return self.quantity * self.market_price


@dataclass(frozen=True, slots=True)
class Portfolio:
    cash: Decimal
    positions: tuple[Position, ...] = field(default_factory=tuple)
    currency: str = "USD"

    def __post_init__(self) -> None:
        object.__setattr__(self, "cash", decimal(self.cash))
        object.__setattr__(self, "positions", tuple(self.positions))
        object.__setattr__(self, "currency", self.currency.strip().upper())

    @property
    def total_value(self) -> Decimal:
        return self.cash + sum((position.value for position in self.positions), ZERO)

    def quantity_of(self, symbol: str) -> Decimal:
        normalized = symbol.strip().upper()
        return sum(
            (position.quantity for position in self.positions if position.symbol == normalized),
            ZERO,
        )

    def value_of(self, symbol: str) -> Decimal:
        normalized = symbol.strip().upper()
        return sum(
            (position.value for position in self.positions if position.symbol == normalized),
            ZERO,
        )

    def weight_of(self, symbol: str) -> Decimal:
        if self.total_value <= ZERO:
            return ZERO
        return pct(self.value_of(symbol) / self.total_value * HUNDRED)

    def prices(self) -> dict[str, Decimal]:
        return {position.symbol: position.market_price for position in self.positions}
