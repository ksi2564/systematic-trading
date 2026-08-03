from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import StrEnum
from hashlib import sha256
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from wallant.domain.money import HUNDRED, ZERO, decimal, money, whole_shares
from wallant.domain.portfolio import Portfolio
from wallant.domain.strategy import EvaluationResult


class AccountStatus(StrEnum):
    ACTIVE = "ACTIVE"
    PAUSED = "PAUSED"


class OrderSide(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class IntentStatus(StrEnum):
    PLANNED = "PLANNED"
    BLOCKED = "BLOCKED"
    PAPER_FILLED = "PAPER_FILLED"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"


class RiskPolicy(BaseModel):
    max_order_notional: Decimal = Field(gt=0)
    max_daily_notional: Decimal = Field(gt=0)
    max_daily_order_count: int = Field(gt=0)
    max_symbol_weight_pct: Decimal = Field(gt=0, le=100)
    max_daily_loss: Decimal = Field(gt=0)
    @model_validator(mode="after")
    def validate_daily_notional(self) -> RiskPolicy:
        if self.max_order_notional > self.max_daily_notional:
            raise ValueError("단일 주문 한도는 일일 주문 합계 한도보다 클 수 없습니다.")
        return self


class DailyRiskUsage(BaseModel):
    order_notional: Decimal = Field(default=ZERO, ge=0)
    order_count: int = Field(default=0, ge=0)
    realized_loss: Decimal = Field(default=ZERO, ge=0)


@dataclass(frozen=True, slots=True)
class PlannedOrderIntent:
    idempotency_key: str
    account_id: UUID
    strategy_version_id: UUID
    signal_date: date
    symbol: str
    side: OrderSide
    quantity: int
    reference_price: Decimal
    target_weight_pct: Decimal
    current_weight_pct: Decimal
    status: IntentStatus
    reason: str
    violations: tuple[str, ...] = field(default_factory=tuple)

    @property
    def notional(self) -> Decimal:
        return money(self.reference_price * self.quantity)


@dataclass(frozen=True, slots=True)
class OrderPlan:
    intents: tuple[PlannedOrderIntent, ...]
    pause_required: bool
    reasons: tuple[str, ...] = field(default_factory=tuple)


class OrderPlanner:
    def plan(
        self,
        *,
        account_id: UUID,
        strategy_version_id: UUID,
        result: EvaluationResult,
        portfolio: Portfolio,
        quotes: dict[str, Decimal],
        tolerance_pct: Decimal,
        risk_policy: RiskPolicy,
        daily_usage: DailyRiskUsage | None = None,
        sell_priority: list[str] | None = None,
        buy_priority: list[str] | None = None,
    ) -> OrderPlan:
        daily_usage = daily_usage or DailyRiskUsage()
        sell_priority = sell_priority or ["TQQQ", "QLD", "QQQM"]
        buy_priority = buy_priority or ["QQQM", "QLD", "TQQQ"]
        total_value = portfolio.total_value
        if total_value <= ZERO:
            return OrderPlan((), False, ("총자산이 0 이하입니다.",))
        if portfolio.quantity_of("QQQ") > ZERO and "QQQ" not in result.target_weights:
            return OrderPlan((), True, ("지원 종료된 QQQ 보유분의 수동 확인이 필요합니다.",))

        raw: list[PlannedOrderIntent] = []
        planned_daily_notional = daily_usage.order_notional
        planned_daily_count = daily_usage.order_count
        pause_reasons: list[str] = []

        for symbol, target_weight in result.target_weights.items():
            target_weight = decimal(target_weight)
            current_weight = portfolio.weight_of(symbol)
            if abs(target_weight - current_weight) < tolerance_pct:
                continue
            quote = decimal(quotes.get(symbol))
            if quote <= ZERO:
                pause_reasons.append(f"{symbol} 필수 가격이 없습니다.")
                continue
            target_value = total_value * target_weight / HUNDRED
            current_value = portfolio.value_of(symbol)
            difference = target_value - current_value
            side = OrderSide.BUY if difference > ZERO else OrderSide.SELL
            desired_quantity = whole_shares(abs(difference) / quote)
            if side == OrderSide.SELL:
                desired_quantity = min(desired_quantity, whole_shares(portfolio.quantity_of(symbol)))
            if desired_quantity <= 0:
                continue

            key = self.idempotency_key(
                account_id,
                strategy_version_id,
                result.as_of,
                symbol,
                side,
                target_weight,
            )
            projected_notional = money(quote * desired_quantity)
            violations: list[str] = []
            if projected_notional > risk_policy.max_order_notional:
                violations.append("MAX_ORDER_NOTIONAL")
            if target_weight > risk_policy.max_symbol_weight_pct:
                violations.append("MAX_SYMBOL_WEIGHT")
            if planned_daily_notional + projected_notional > risk_policy.max_daily_notional:
                violations.append("MAX_DAILY_NOTIONAL")
            if planned_daily_count + 1 > risk_policy.max_daily_order_count:
                violations.append("MAX_DAILY_ORDER_COUNT")
            if daily_usage.realized_loss >= risk_policy.max_daily_loss:
                violations.append("MAX_DAILY_LOSS")

            status = IntentStatus.BLOCKED if violations else IntentStatus.PLANNED
            reason = (
                f"목표 {target_weight}% / 현재 {current_weight}%"
                if not violations
                else f"위험 한도 위반: {', '.join(violations)}"
            )
            raw.append(
                PlannedOrderIntent(
                    idempotency_key=key,
                    account_id=account_id,
                    strategy_version_id=strategy_version_id,
                    signal_date=result.as_of,
                    symbol=symbol,
                    side=side,
                    quantity=desired_quantity,
                    reference_price=quote,
                    target_weight_pct=target_weight,
                    current_weight_pct=current_weight,
                    status=status,
                    reason=reason,
                    violations=tuple(violations),
                )
            )
            if status == IntentStatus.PLANNED:
                planned_daily_notional += projected_notional
                planned_daily_count += 1
            pause_reasons.extend(violations)

        intents = tuple(
            sorted(
                raw,
                key=lambda intent: (
                    0 if intent.side == OrderSide.SELL else 1,
                    self._priority(intent, sell_priority, buy_priority),
                ),
            )
        )
        return OrderPlan(
            intents=intents,
            pause_required=bool(pause_reasons),
            reasons=tuple(dict.fromkeys(pause_reasons)),
        )

    @staticmethod
    def idempotency_key(
        account_id: UUID,
        strategy_version_id: UUID,
        signal_date: date,
        symbol: str,
        side: OrderSide,
        target_weight: Decimal,
    ) -> str:
        raw = "|".join(
            [
                str(account_id),
                str(strategy_version_id),
                signal_date.isoformat(),
                symbol.strip().upper(),
                side.value,
                str(target_weight.normalize()),
            ]
        )
        return sha256(raw.encode("utf-8")).hexdigest()

    @staticmethod
    def _priority(
        intent: PlannedOrderIntent,
        sell_priority: list[str],
        buy_priority: list[str],
    ) -> int:
        priorities = sell_priority if intent.side == OrderSide.SELL else buy_priority
        try:
            return priorities.index(intent.symbol)
        except ValueError:
            return len(priorities)
