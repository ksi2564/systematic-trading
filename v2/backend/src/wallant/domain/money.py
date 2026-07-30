from __future__ import annotations

from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal
from typing import Any

ZERO = Decimal("0")
HUNDRED = Decimal("100")


def decimal(value: Any, default: Decimal = ZERO) -> Decimal:
    if value is None:
        return default
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def pct(value: Any) -> Decimal:
    return decimal(value).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def money(value: Any) -> Decimal:
    return decimal(value).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def whole_shares(value: Decimal) -> int:
    return int(value.quantize(Decimal("1"), rounding=ROUND_DOWN))
