from __future__ import annotations

from abc import ABC, abstractmethod
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from wallant.domain.money import HUNDRED, ZERO, decimal
from wallant.domain.strategy import (
    Condition,
    ConditionGroup,
    ConditionOperator,
    EvaluationContext,
    EvaluationResult,
    Operand,
    StrategyDefinition,
    StrategyEngine,
    StrategyVersion,
)


class StrategyEvaluationError(ValueError):
    """전략을 결정론적으로 평가할 수 없을 때 발생한다."""


def apply_position_protections(
    definition: StrategyDefinition,
    context: EvaluationContext,
    target: dict[str, Decimal],
) -> tuple[dict[str, Decimal], list[str]]:
    rules = definition.protections
    return_pct = QqqmDrawdownEvaluator._optional_decimal(context.portfolio.get("unrealized_return_pct"))
    peak_return_pct = QqqmDrawdownEvaluator._optional_decimal(context.portfolio.get("peak_return_pct"))
    events: list[str] = []
    if rules.stop_loss_pct is not None and return_pct is not None and return_pct <= -rules.stop_loss_pct:
        events.append("STOP_LOSS")
    if rules.take_profit_pct is not None and return_pct is not None and return_pct >= rules.take_profit_pct:
        events.append("TAKE_PROFIT")
    if (
        rules.trailing_stop_pct is not None
        and return_pct is not None
        and peak_return_pct is not None
        and peak_return_pct - return_pct >= rules.trailing_stop_pct
    ):
        events.append("TRAILING_STOP")
    if events:
        return {symbol: ZERO for symbol in target}, events
    return target, events


class StrategyEvaluator(ABC):
    @abstractmethod
    def evaluate(self, version: StrategyVersion, context: EvaluationContext) -> EvaluationResult:
        raise NotImplementedError


class QqqmDrawdownEvaluator(StrategyEvaluator):
    """Java v2 전략의 실제 상태 전이와 주문 직전 보정을 재현한다."""

    DEFAULT_DRAWDOWN_THRESHOLDS = (
        Decimal("15"),
        Decimal("25"),
        Decimal("35"),
        Decimal("45"),
    )
    DEFAULT_RECOVERY_ACTIVATION = Decimal("15")
    DEFAULT_RECOVERY_DRAWDOWN = Decimal("10")

    def evaluate(self, version: StrategyVersion, context: EvaluationContext) -> EvaluationResult:
        definition = version.definition
        signal = definition.signal_symbol
        close = self._required_decimal(context.market, f"{signal}.close", fallback_key="signal_close")
        previous = context.previous_state
        thresholds = self._thresholds(definition)
        activation = decimal(
            definition.parameters.get("recovery_activation_max_drawdown_pct"),
            self.DEFAULT_RECOVERY_ACTIVATION,
        )
        recovery = decimal(
            definition.parameters.get("recovery_drawdown_pct"),
            self.DEFAULT_RECOVERY_DRAWDOWN,
        )

        if previous and str(previous.get("signal_symbol", "")).upper() == signal:
            state = self._next_state(
                signal=signal,
                close=close,
                previous=previous,
                thresholds=thresholds,
                activation=activation,
                recovery=recovery,
            )
        else:
            try:
                ath_lookup_days = int(definition.parameters.get("ath_lookup_days", 365))
            except (TypeError, ValueError) as exc:
                raise StrategyEvaluationError("ATH 조회 기간은 1 이상의 정수여야 합니다.") from exc
            if ath_lookup_days < 1:
                raise StrategyEvaluationError("ATH 조회 기간은 1 이상의 정수여야 합니다.")
            state = self._initial_state(
                signal=signal,
                close=close,
                history=context.history.get(signal, [])[-ath_lookup_days:],
                thresholds=thresholds,
                activation=activation,
                recovery=recovery,
            )

        base_weights = {symbol: decimal(weight) for symbol, weight in state["base_target_weights"].items()}
        adjusted, events = self._apply_circuit_breakers(
            definition=definition,
            close=close,
            market=context.market,
            original=base_weights,
            previous=context.previous_target_weights,
        )
        explanation = [
            f"{signal} ATH {state['ath']} 대비 낙폭 {state['drawdown_pct']}%",
            f"최대 낙폭 {state['max_drawdown_pct_since_ath']}%, 단계 {state['phase']}",
            f"기본 목표 비중 {self._weights_text(base_weights)}",
        ]
        if events:
            explanation.append(f"보호 필터 적용: {', '.join(events)}")

        protected, protection_events = apply_position_protections(definition, context, adjusted)
        events.extend(protection_events)
        if protection_events:
            explanation.append(f"포지션 보호 규칙 적용: {', '.join(protection_events)}")

        state["target_weights"] = {symbol: str(weight) for symbol, weight in protected.items()}
        return EvaluationResult(
            as_of=context.as_of,
            strategy_version_id=version.id,
            engine=definition.engine,
            state=state,
            target_weights=protected,
            base_target_weights=base_weights,
            events=events,
            explanation=explanation,
        )

    def _initial_state(
        self,
        *,
        signal: str,
        close: Decimal,
        history: list[Decimal],
        thresholds: tuple[Decimal, Decimal, Decimal, Decimal],
        activation: Decimal,
        recovery: Decimal,
    ) -> dict[str, Any]:
        prices = [decimal(value) for value in history]
        ath = max([close, *prices]) if prices else close
        drawdown = self._drawdown(ath, close)
        max_drawdown = drawdown
        phase = self._phase(max_drawdown, drawdown, activation, recovery)
        weights = self._weights_for_phase(phase, drawdown, thresholds)
        return self._state(
            signal,
            ath,
            close,
            drawdown,
            max_drawdown,
            thresholds,
            phase,
            weights,
            True,
        )

    def _next_state(
        self,
        *,
        signal: str,
        close: Decimal,
        previous: dict[str, Any],
        thresholds: tuple[Decimal, Decimal, Decimal, Decimal],
        activation: Decimal,
        recovery: Decimal,
    ) -> dict[str, Any]:
        previous_ath = decimal(previous.get("ath"), close)
        if close > previous_ath:
            ath = close
            drawdown = ZERO
            max_drawdown = ZERO
        else:
            ath = previous_ath
            drawdown = self._drawdown(ath, close)
            max_drawdown = max(
                decimal(previous.get("max_drawdown_pct_since_ath")),
                drawdown,
            )
        phase = self._phase(max_drawdown, drawdown, activation, recovery)
        if phase == "DRAWDOWN" and recovery < drawdown < activation:
            previous_weights = (
                previous.get("base_target_weights")
                or previous.get("target_weights")
                or self._weights_for_phase(phase, drawdown, thresholds)
            )
            weights = {symbol: decimal(weight) for symbol, weight in previous_weights.items()}
        else:
            weights = self._weights_for_phase(phase, drawdown, thresholds)
        return self._state(
            signal,
            ath,
            close,
            drawdown,
            max_drawdown,
            thresholds,
            phase,
            weights,
            bool(previous.get("strategy_on", True)),
        )

    def _state(
        self,
        signal: str,
        ath: Decimal,
        close: Decimal,
        drawdown: Decimal,
        max_drawdown: Decimal,
        thresholds: tuple[Decimal, Decimal, Decimal, Decimal],
        phase: str,
        weights: dict[str, Decimal],
        strategy_on: bool,
    ) -> dict[str, Any]:
        return {
            "signal_symbol": signal,
            "ath": str(ath),
            "last_close": str(close),
            "drawdown_pct": str(drawdown),
            "max_drawdown_pct_since_ath": str(max_drawdown),
            "drawdown_bucket": self._bucket(drawdown, thresholds),
            "phase": phase,
            "base_target_weights": {symbol: str(weight) for symbol, weight in weights.items()},
            "strategy_on": strategy_on,
            "legacy_strategy_version": 2,
        }

    def _apply_circuit_breakers(
        self,
        *,
        definition: StrategyDefinition,
        close: Decimal,
        market: dict[str, Decimal | str | bool | None],
        original: dict[str, Decimal],
        previous: dict[str, Decimal],
    ) -> tuple[dict[str, Decimal], list[str]]:
        if not bool(definition.parameters.get("circuit_breaker_enabled", True)):
            return original, []

        adjusted = dict(original)
        events: list[str] = []
        ma_period = int(definition.parameters.get("ma_period", 200))
        ma = self._optional_decimal(
            market.get(f"{definition.signal_symbol}.ma_{ma_period}", market.get("signal_ma_200"))
        )
        if ma is not None and close < ma:
            adjusted = self._reduce_one_bucket(adjusted, definition.signal_symbol)
            events.append(f"MA{ma_period}_DEFENSIVE")

        vix = self._optional_decimal(market.get("VIX.close", market.get("vix")))
        vix_enabled = bool(definition.parameters.get("vix_enabled", True))
        vix_threshold = decimal(definition.parameters.get("vix_threshold"), Decimal("35"))
        if vix_enabled and vix is not None and vix >= vix_threshold:
            previous_weights = {
                symbol.strip().upper(): decimal(weight) for symbol, weight in previous.items()
            }
            if previous_weights and adjusted.get("TQQQ", ZERO) > previous_weights.get("TQQQ", ZERO):
                adjusted = previous_weights
            events.append("VIX_NO_LEVERAGE_INCREASE")
        return adjusted, events

    def _weights_for_phase(
        self,
        phase: str,
        drawdown: Decimal,
        thresholds: tuple[Decimal, Decimal, Decimal, Decimal],
    ) -> dict[str, Decimal]:
        if phase == "NORMAL":
            return self._weights(100, 0, 0)
        if phase == "RECOVERY":
            return self._weights(70, 30, 0)
        bucket = self._bucket(drawdown, thresholds)
        return {
            "LESS_THAN_15": self._weights(100, 0, 0),
            "FROM_15_TO_25": self._weights(60, 30, 10),
            "FROM_25_TO_35": self._weights(40, 40, 20),
            "FROM_35_TO_45": self._weights(30, 30, 40),
            "MORE_THAN_45": self._weights(20, 20, 60),
        }[bucket]

    @staticmethod
    def _weights(base: int, qld: int, tqqq: int) -> dict[str, Decimal]:
        return {
            "QQQM": Decimal(base).quantize(Decimal("0.00")),
            "QLD": Decimal(qld).quantize(Decimal("0.00")),
            "TQQQ": Decimal(tqqq).quantize(Decimal("0.00")),
        }

    @staticmethod
    def _thresholds(definition: StrategyDefinition) -> tuple[Decimal, Decimal, Decimal, Decimal]:
        raw = definition.parameters.get(
            "drawdown_thresholds", QqqmDrawdownEvaluator.DEFAULT_DRAWDOWN_THRESHOLDS
        )
        values = tuple(decimal(value) for value in raw)
        if len(values) != 4 or list(values) != sorted(values) or values[0] <= ZERO:
            raise StrategyEvaluationError("낙폭 임계값 네 개는 양수이며 오름차순이어야 합니다.")
        return values  # type: ignore[return-value]

    @staticmethod
    def _phase(
        max_drawdown: Decimal,
        drawdown: Decimal,
        activation: Decimal,
        recovery: Decimal,
    ) -> str:
        if max_drawdown < activation:
            return "NORMAL"
        return "RECOVERY" if drawdown <= recovery else "DRAWDOWN"

    @staticmethod
    def _bucket(
        drawdown: Decimal,
        thresholds: tuple[Decimal, Decimal, Decimal, Decimal],
    ) -> str:
        first, second, third, fourth = thresholds
        if drawdown < first:
            return "LESS_THAN_15"
        if drawdown < second:
            return "FROM_15_TO_25"
        if drawdown < third:
            return "FROM_25_TO_35"
        if drawdown < fourth:
            return "FROM_35_TO_45"
        return "MORE_THAN_45"

    @staticmethod
    def _drawdown(ath: Decimal, close: Decimal) -> Decimal:
        if ath == ZERO:
            return ZERO.quantize(Decimal("0.0000"))
        return ((ath - close) / ath * HUNDRED).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)

    @staticmethod
    def _reduce_one_bucket(weights: dict[str, Decimal], base_symbol: str) -> dict[str, Decimal]:
        tqqq = weights.get("TQQQ", ZERO)
        if tqqq >= 60:
            values = (30, 30, 40)
        elif tqqq >= 40:
            values = (40, 40, 20)
        elif tqqq >= 20:
            values = (60, 30, 10)
        elif tqqq >= 10:
            values = (100, 0, 0)
        else:
            return weights
        base, qld, tqqq_value = values
        return {
            base_symbol: Decimal(base).quantize(Decimal("0.00")),
            "QLD": Decimal(qld).quantize(Decimal("0.00")),
            "TQQQ": Decimal(tqqq_value).quantize(Decimal("0.00")),
        }

    @staticmethod
    def _required_decimal(
        values: dict[str, Decimal | str | bool | None],
        key: str,
        *,
        fallback_key: str | None = None,
    ) -> Decimal:
        value = values.get(key)
        if value is None and fallback_key:
            value = values.get(fallback_key)
        if value is None:
            raise StrategyEvaluationError(f"필수 시장 데이터가 없습니다: {key}")
        return decimal(value)

    @staticmethod
    def _optional_decimal(value: Any) -> Decimal | None:
        return None if value is None or isinstance(value, bool) else decimal(value)

    @staticmethod
    def _weights_text(weights: dict[str, Decimal]) -> str:
        return ", ".join(f"{symbol} {weight}%" for symbol, weight in weights.items())


class RuleAllocationEvaluator(StrategyEvaluator):
    def evaluate(self, version: StrategyVersion, context: EvaluationContext) -> EvaluationResult:
        definition = version.definition
        matched = None
        for rule in sorted(definition.rules, key=lambda item: item.priority):
            if self._matches(rule.when, context):
                matched = rule
                break
        if matched is None:
            raise StrategyEvaluationError("현재 데이터와 일치하는 배분 규칙이 없습니다.")

        target_symbols = dict.fromkeys(
            [*definition.universe.symbols, *matched.target_weights],
        )
        base_target = {
            symbol: decimal(matched.target_weights.get(symbol), ZERO) for symbol in target_symbols
        }
        target, protection_events = apply_position_protections(
            definition,
            context,
            base_target,
        )
        state = dict(context.previous_state)
        if matched.next_state:
            state["name"] = matched.next_state
        state["matched_rule"] = matched.name
        events = [f"RULE_MATCHED:{matched.name}", *protection_events]
        explanation = [f"우선순위 {matched.priority} 규칙 '{matched.name}'이 일치했습니다."]
        if protection_events:
            explanation.append(f"포지션 보호 규칙 적용: {', '.join(protection_events)}")
        return EvaluationResult(
            as_of=context.as_of,
            strategy_version_id=version.id,
            engine=definition.engine,
            state=state,
            target_weights=target,
            base_target_weights=base_target,
            events=events,
            explanation=explanation,
        )

    def _matches(self, group: ConditionGroup, context: EvaluationContext) -> bool:
        values = [self._condition(condition, context) for condition in group.conditions]
        return all(values) if group.mode == "ALL" else any(values)

    def _condition(self, condition: Condition, context: EvaluationContext) -> bool:
        left = self._operand(condition.left, context)
        right = self._operand(condition.right, context)
        upper = self._operand(condition.upper, context) if condition.upper else None
        comparable = self._normalize_comparable(left, right, upper)
        left, right, upper = comparable
        operator = condition.operator
        try:
            if operator == ConditionOperator.EQ:
                return left == right
            if operator == ConditionOperator.NE:
                return left != right
            if operator == ConditionOperator.GT:
                return left > right
            if operator == ConditionOperator.GTE:
                return left >= right
            if operator == ConditionOperator.LT:
                return left < right
            if operator == ConditionOperator.LTE:
                return left <= right
            return right <= left <= upper
        except TypeError as exc:
            raise StrategyEvaluationError(
                f"조건식 값을 비교할 수 없습니다: {left!r}, {right!r}, {upper!r}"
            ) from exc

    @staticmethod
    def _normalize_comparable(left: Any, right: Any, upper: Any) -> tuple[Any, Any, Any]:
        values = [left, right] if upper is None else [left, right, upper]
        if any(isinstance(value, bool) for value in values):
            return left, right, upper
        try:
            normalized = [decimal(value) for value in values]
        except (ValueError, TypeError):
            return left, right, upper
        if upper is None:
            return normalized[0], normalized[1], None
        return normalized[0], normalized[1], normalized[2]

    @staticmethod
    def _operand(operand: Operand, context: EvaluationContext) -> Any:
        if operand.source == "constant":
            return operand.value
        source = {
            "market": context.market,
            "state": context.previous_state,
            "portfolio": context.portfolio,
        }[operand.source]
        if operand.key not in source:
            raise StrategyEvaluationError(f"조건식 데이터가 없습니다: {operand.source}.{operand.key}")
        return source[operand.key]


class EvaluatorRegistry:
    def __init__(self) -> None:
        self._evaluators: dict[StrategyEngine, StrategyEvaluator] = {
            StrategyEngine.QQQM_DRAWDOWN_V2: QqqmDrawdownEvaluator(),
            StrategyEngine.RULE_ALLOCATION_V1: RuleAllocationEvaluator(),
        }

    def evaluate(self, version: StrategyVersion, context: EvaluationContext) -> EvaluationResult:
        try:
            evaluator = self._evaluators[version.definition.engine]
        except KeyError as exc:
            raise StrategyEvaluationError(
                f"지원하지 않는 전략 엔진입니다: {version.definition.engine}"
            ) from exc
        return evaluator.evaluate(version, context)
