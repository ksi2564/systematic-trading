from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import StrEnum
from hashlib import sha256
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from wallant.domain.money import HUNDRED, ZERO, decimal, money, whole_shares
from wallant.domain.portfolio import Portfolio
from wallant.domain.strategy import EvaluationResult, StrategyEngine


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
    model_config = ConfigDict(extra="forbid")

    max_buy_order_notional: Decimal = Field(gt=0)
    max_daily_buy_notional: Decimal = Field(gt=0)
    max_daily_order_count: int = Field(gt=0)
    max_symbol_weight_pct: Decimal = Field(gt=0, le=100)
    max_daily_loss: Decimal = Field(gt=0)
    @model_validator(mode="after")
    def validate_daily_buy_notional(self) -> RiskPolicy:
        if self.max_buy_order_notional > self.max_daily_buy_notional:
            raise ValueError("단일 매수 한도는 일일 매수 합계 한도보다 클 수 없습니다.")
        return self


class DailyRiskUsage(BaseModel):
    buy_notional: Decimal = Field(default=ZERO, ge=0)
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


@dataclass(frozen=True, slots=True)
class _CandidateOrder:
    idempotency_key: str
    symbol: str
    side: OrderSide
    quantity: int
    reference_price: Decimal
    target_weight_pct: Decimal
    current_weight_pct: Decimal

    @property
    def notional(self) -> Decimal:
        return money(self.reference_price * self.quantity)


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
        idempotency_scope: str = "LIVE",
    ) -> OrderPlan:
        daily_usage = daily_usage or DailyRiskUsage()
        sell_priority = sell_priority or ["TQQQ", "QLD", "QQQM"]
        buy_priority = buy_priority or ["QQQM", "QLD", "TQQQ"]
        total_value = portfolio.total_value
        unmanaged_symbols = sorted(
            {
                position.symbol
                for position in portfolio.positions
                if position.quantity > ZERO and position.symbol not in result.target_weights
            }
        )
        if unmanaged_symbols:
            return OrderPlan(
                (),
                True,
                tuple(
                    f"전략 관리 대상이 아닌 {symbol} 보유분의 수동 확인이 필요합니다."
                    for symbol in unmanaged_symbols
                ),
            )
        if total_value <= ZERO:
            return OrderPlan((), True, ("총자산이 0 이하입니다.",))

        force_signal_rebalance = result.engine == StrategyEngine.SIGNAL_TRADING_V1 and (
            "SIGNAL_ENTRY" in result.events or bool(result.state.get("exit_pending", False))
        )
        managed_targets: list[tuple[str, Decimal, Decimal]] = []
        for symbol, target_weight in result.target_weights.items():
            target_weight = decimal(target_weight)
            current_weight = portfolio.weight_of(symbol)
            managed_targets.append((symbol, target_weight, current_weight))

        should_rebalance = force_signal_rebalance or any(
            abs(target_weight - current_weight) >= tolerance_pct
            for _symbol, target_weight, current_weight in managed_targets
        )
        rebalance_targets = [
            (symbol, target_weight, current_weight)
            for symbol, target_weight, current_weight in managed_targets
            if should_rebalance and target_weight != current_weight
        ]

        missing_quotes = [
            symbol
            for symbol, _target_weight, _current_weight in rebalance_targets
            if decimal(quotes.get(symbol)) <= ZERO
        ]
        if missing_quotes:
            return OrderPlan(
                (),
                True,
                tuple(f"{symbol} 필수 가격이 없습니다." for symbol in missing_quotes),
            )

        candidates: list[_CandidateOrder] = []
        for symbol, target_weight, current_weight in rebalance_targets:
            quote = decimal(quotes[symbol])
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
                idempotency_scope,
            )
            candidates.append(
                _CandidateOrder(
                    idempotency_key=key,
                    symbol=symbol,
                    side=side,
                    quantity=desired_quantity,
                    reference_price=quote,
                    target_weight_pct=target_weight,
                    current_weight_pct=current_weight,
                )
            )

        ordered_candidates = sorted(
            candidates,
            key=lambda candidate: (
                0 if candidate.side == OrderSide.SELL else 1,
                self._symbol_priority(
                    candidate.symbol,
                    candidate.side,
                    sell_priority,
                    buy_priority,
                ),
                candidate.symbol,
            ),
        )
        raw: list[PlannedOrderIntent] = []
        planned_daily_buy_notional = daily_usage.buy_notional
        planned_daily_count = daily_usage.order_count
        pause_reasons: list[str] = []
        for candidate in ordered_candidates:
            projected_notional = candidate.notional
            violations: list[str] = []
            if planned_daily_count + 1 > risk_policy.max_daily_order_count:
                violations.append("MAX_DAILY_ORDER_COUNT")
            if candidate.side == OrderSide.BUY:
                if projected_notional > risk_policy.max_buy_order_notional:
                    violations.append("MAX_BUY_ORDER_NOTIONAL")
                if candidate.target_weight_pct > risk_policy.max_symbol_weight_pct:
                    violations.append("MAX_SYMBOL_WEIGHT")
                if (
                    planned_daily_buy_notional + projected_notional
                    > risk_policy.max_daily_buy_notional
                ):
                    violations.append("MAX_DAILY_BUY_NOTIONAL")
                if daily_usage.realized_loss >= risk_policy.max_daily_loss:
                    violations.append("MAX_DAILY_LOSS")

            if not violations:
                intent_status = IntentStatus.PLANNED
            elif candidate.side == OrderSide.SELL:
                intent_status = IntentStatus.CONFIRMATION_REQUIRED
            else:
                intent_status = IntentStatus.BLOCKED
            reason = (
                f"목표 {candidate.target_weight_pct}% / 현재 {candidate.current_weight_pct}%"
                if not violations
                else f"주문 안전 한도 위반: {', '.join(violations)}"
            )
            raw.append(
                PlannedOrderIntent(
                    idempotency_key=candidate.idempotency_key,
                    account_id=account_id,
                    strategy_version_id=strategy_version_id,
                    signal_date=result.as_of,
                    symbol=candidate.symbol,
                    side=candidate.side,
                    quantity=candidate.quantity,
                    reference_price=candidate.reference_price,
                    target_weight_pct=candidate.target_weight_pct,
                    current_weight_pct=candidate.current_weight_pct,
                    status=intent_status,
                    reason=reason,
                    violations=tuple(violations),
                )
            )
            if intent_status == IntentStatus.PLANNED:
                planned_daily_count += 1
                if candidate.side == OrderSide.BUY:
                    planned_daily_buy_notional += projected_notional
            pause_reasons.extend(violations)

        return OrderPlan(
            intents=tuple(raw),
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
        scope: str = "LIVE",
    ) -> str:
        raw = "|".join(
            [
                str(account_id),
                str(strategy_version_id),
                scope,
                signal_date.isoformat(),
                symbol.strip().upper(),
                side.value,
                str(target_weight.normalize()),
            ]
        )
        return sha256(raw.encode("utf-8")).hexdigest()

    @staticmethod
    def _symbol_priority(
        symbol: str,
        side: OrderSide,
        sell_priority: list[str],
        buy_priority: list[str],
    ) -> int:
        priorities = sell_priority if side == OrderSide.SELL else buy_priority
        try:
            return priorities.index(symbol)
        except ValueError:
            return len(priorities)
