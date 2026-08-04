from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from uuid import UUID, uuid4

from wallant.domain.evaluator import EvaluatorRegistry
from wallant.domain.execution import (
    DailyRiskUsage,
    IntentStatus,
    OrderPlan,
    OrderPlanner,
    OrderSide,
    RiskPolicy,
)
from wallant.domain.money import ZERO, money
from wallant.domain.portfolio import Portfolio
from wallant.domain.strategy import EvaluationContext, EvaluationResult, StrategyVersion


@dataclass(slots=True)
class PaperSession:
    id: UUID = field(default_factory=uuid4)
    account_id: UUID = field(default_factory=uuid4)
    previous_state: dict = field(default_factory=dict)
    previous_target_weights: dict[str, Decimal] = field(default_factory=dict)
    evaluated_days: int = 0
    signal_count: int = 0
    error_count: int = 0


@dataclass(frozen=True, slots=True)
class PaperStepResult:
    evaluation: EvaluationResult
    order_plan: OrderPlan
    realized_loss: Decimal
    evidence: dict[str, int]


class PaperTradingService:
    def __init__(
        self,
        registry: EvaluatorRegistry | None = None,
        order_planner: OrderPlanner | None = None,
    ) -> None:
        self.registry = registry or EvaluatorRegistry()
        self.order_planner = order_planner or OrderPlanner()

    def step(
        self,
        *,
        session: PaperSession,
        version: StrategyVersion,
        context: EvaluationContext,
        portfolio: Portfolio,
        quotes: dict[str, Decimal],
        risk_policy: RiskPolicy,
        daily_usage: DailyRiskUsage | None = None,
    ) -> PaperStepResult:
        symbol = version.definition.signal_symbol
        positions = [position for position in portfolio.positions if position.symbol == symbol]
        quantity = sum((position.quantity for position in positions), ZERO)
        average_price = (
            sum((position.average_price * position.quantity for position in positions), ZERO) / quantity
            if quantity > ZERO
            else ZERO
        )
        merged_context = context.model_copy(
            update={
                "previous_state": session.previous_state,
                "previous_target_weights": session.previous_target_weights,
                "portfolio": {
                    **context.portfolio,
                    **{
                        f"{item}.weight_pct": portfolio.weight_of(item)
                        for item in version.definition.universe.symbols
                    },
                    "position_open": quantity > ZERO,
                    "quantity": quantity,
                    "average_price": average_price,
                    "cash": portfolio.cash,
                    "total_value": portfolio.total_value,
                },
            }
        )
        try:
            evaluation = self.registry.evaluate(version, merged_context)
            plan = self.order_planner.plan(
                account_id=session.account_id,
                strategy_version_id=version.id,
                result=evaluation,
                portfolio=portfolio,
                quotes=quotes,
                tolerance_pct=version.definition.tolerance_pct,
                risk_policy=risk_policy,
                daily_usage=daily_usage,
                sell_priority=version.definition.parameters.get("sell_priority"),
                buy_priority=version.definition.parameters.get("buy_priority"),
                idempotency_scope=str(session.id),
            )
            realized_loss = self._realized_loss(plan, portfolio)
        except Exception:
            session.error_count += 1
            raise

        session.previous_state = evaluation.state
        session.previous_target_weights = evaluation.target_weights
        session.evaluated_days += 1
        session.signal_count += len(plan.intents)
        return PaperStepResult(
            evaluation=evaluation,
            order_plan=plan,
            realized_loss=realized_loss,
            evidence={
                "evaluated_days": session.evaluated_days,
                "signal_count": session.signal_count,
                "error_count": session.error_count,
            },
        )

    @staticmethod
    def _realized_loss(plan: OrderPlan, portfolio: Portfolio) -> Decimal:
        loss = ZERO
        for intent in plan.intents:
            if intent.status == IntentStatus.BLOCKED or intent.side != OrderSide.SELL:
                continue
            positions = [
                position for position in portfolio.positions if position.symbol == intent.symbol
            ]
            quantity = sum((position.quantity for position in positions), ZERO)
            if quantity <= ZERO:
                continue
            average_price = (
                sum(
                    (position.average_price * position.quantity for position in positions),
                    ZERO,
                )
                / quantity
            )
            loss += max(average_price - intent.reference_price, ZERO) * intent.quantity
        return money(loss)
