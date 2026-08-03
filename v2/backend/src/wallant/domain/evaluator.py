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
    SignalKind,
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
    trailing_drawdown_pct = QqqmDrawdownEvaluator._optional_decimal(
        context.portfolio.get("trailing_drawdown_pct")
    )
    events: list[str] = []
    if rules.stop_loss_pct is not None and return_pct is not None and return_pct <= -rules.stop_loss_pct:
        events.append("STOP_LOSS")
    if rules.take_profit_pct is not None and return_pct is not None and return_pct >= rules.take_profit_pct:
        events.append("TAKE_PROFIT")
    if (
        rules.trailing_stop_pct is not None
        and trailing_drawdown_pct is not None
        and trailing_drawdown_pct >= rules.trailing_stop_pct
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

        state["target_weights"] = {symbol: str(weight) for symbol, weight in adjusted.items()}
        return EvaluationResult(
            as_of=context.as_of,
            strategy_version_id=version.id,
            engine=definition.engine,
            state=state,
            target_weights=adjusted,
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
        state = dict(context.previous_state)
        if matched.next_state:
            state["name"] = matched.next_state
        state["matched_rule"] = matched.name
        events = [f"RULE_MATCHED:{matched.name}"]
        explanation = [f"우선순위 {matched.priority} 규칙 '{matched.name}'이 일치했습니다."]
        return EvaluationResult(
            as_of=context.as_of,
            strategy_version_id=version.id,
            engine=definition.engine,
            state=state,
            target_weights=base_target,
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


class SignalTradingEvaluator(StrategyEvaluator):
    """개별종목의 새 진입 신호와 자연 청산·보호 청산을 평가한다."""

    def evaluate(self, version: StrategyVersion, context: EvaluationContext) -> EvaluationResult:
        definition = version.definition
        rules = definition.signal_rules
        if rules is None:
            raise StrategyEvaluationError("개별종목 신호 설정이 없습니다.")

        symbol = definition.signal_symbol
        close = QqqmDrawdownEvaluator._required_decimal(context.market, f"{symbol}.close")
        history = [decimal(value) for value in context.history.get(symbol, [])]
        entry_condition, exit_condition, indicator = self._conditions(rules.kind, rules, history, close)
        previous_entry_condition = bool(context.previous_state.get("entry_condition", False))
        fresh_entry = entry_condition and not previous_entry_condition
        position_open = bool(context.portfolio.get("position_open", False))
        target_weight = rules.target_weight_pct if position_open else ZERO
        events: list[str] = []
        explanation = [indicator]

        if position_open and exit_condition:
            target_weight = ZERO
            events.append("SIGNAL_EXIT")
            explanation.append("자연 청산 신호가 발생했습니다.")
        elif not position_open and fresh_entry:
            target_weight = rules.target_weight_pct
            events.append("SIGNAL_ENTRY")
            explanation.append("새 매수 신호가 발생했습니다.")

        base_target = {symbol: target_weight}
        peak_price: Decimal | None = None
        if position_open:
            average_price = self._required_portfolio_decimal(context, "average_price")
            previous_peak = QqqmDrawdownEvaluator._optional_decimal(
                context.previous_state.get("peak_price")
            )
            peak_price = max(close, previous_peak or close)
            return_pct = (close - average_price) / average_price * HUNDRED
            trailing_drawdown_pct = (peak_price - close) / peak_price * HUNDRED
            protected, protection_events = apply_position_protections(
                definition,
                context.model_copy(
                    update={
                        "portfolio": {
                            **context.portfolio,
                            "unrealized_return_pct": return_pct,
                            "trailing_drawdown_pct": trailing_drawdown_pct,
                        }
                    }
                ),
                {symbol: target_weight},
            )
            target_weight = protected[symbol]
            events.extend(protection_events)
            if protection_events:
                explanation.append(f"보호 청산 규칙 적용: {', '.join(protection_events)}")

        state = {
            "signal_symbol": symbol,
            "signal_kind": rules.kind.value,
            "entry_condition": entry_condition,
            "position_open": position_open,
            "peak_price": str(peak_price) if peak_price is not None and target_weight > ZERO else None,
            "strategy_on": True,
        }
        target = {symbol: target_weight}
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

    def _conditions(self, kind, rules, history, close):
        if kind == SignalKind.PRICE_MA_CROSS:
            period = rules.ma_period
            self._require_history(history, period, f"MA{period}")
            previous_close = history[-1]
            previous_ma = self._mean(history[-period:])
            current_ma = self._mean([*history[-(period - 1) :], close])
            return (
                previous_close <= previous_ma and close > current_ma,
                previous_close >= previous_ma and close < current_ma,
                f"종가 {close}, MA{period} {current_ma}",
            )
        if kind == SignalKind.MA_CROSS:
            self._require_history(history, rules.slow_period, f"MA{rules.slow_period}")
            previous_fast = self._mean(history[-rules.fast_period :])
            previous_slow = self._mean(history[-rules.slow_period :])
            current_fast = self._mean([*history[-(rules.fast_period - 1) :], close])
            current_slow = self._mean([*history[-(rules.slow_period - 1) :], close])
            return (
                previous_fast <= previous_slow and current_fast > current_slow,
                previous_fast >= previous_slow and current_fast < current_slow,
                f"단기선 {current_fast}, 장기선 {current_slow}",
            )
        if kind == SignalKind.HIGH_BREAKOUT:
            self._require_history(history, rules.breakout_period, f"{rules.breakout_period}일 돌파")
            prior = history[-rules.breakout_period :]
            return (
                close > max(prior),
                close < min(prior),
                f"종가 {close}, 이전 고가 {max(prior)}, 이전 저가 {min(prior)}",
            )

        period = rules.rsi_period
        self._require_history(history, period + 1, f"RSI{period}")
        previous_rsi = self._rsi(history, period)
        current_rsi = self._rsi([*history, close], period)
        return (
            previous_rsi <= rules.rsi_entry_threshold < current_rsi,
            current_rsi >= rules.rsi_exit_threshold,
            f"RSI{period} {current_rsi}",
        )

    @staticmethod
    def _require_history(history: list[Decimal], count: int, label: str) -> None:
        if len(history) < count:
            raise StrategyEvaluationError(f"{label} 계산에 필요한 과거 데이터가 부족합니다.")

    @staticmethod
    def _mean(values: list[Decimal]) -> Decimal:
        return sum(values, ZERO) / len(values)

    @staticmethod
    def _rsi(values: list[Decimal], period: int) -> Decimal:
        changes = [
            current - previous for previous, current in zip(values, values[1:], strict=False)
        ]
        initial = changes[:period]
        gains = sum((change for change in initial if change > ZERO), ZERO) / period
        losses = sum((-change for change in initial if change < ZERO), ZERO) / period
        for change in changes[period:]:
            gain = change if change > ZERO else ZERO
            loss = -change if change < ZERO else ZERO
            gains = (gains * (period - 1) + gain) / period
            losses = (losses * (period - 1) + loss) / period
        if losses == ZERO:
            return HUNDRED if gains > ZERO else Decimal("50")
        return HUNDRED - HUNDRED / (Decimal("1") + gains / losses)

    @staticmethod
    def _required_portfolio_decimal(context: EvaluationContext, key: str) -> Decimal:
        value = QqqmDrawdownEvaluator._optional_decimal(context.portfolio.get(key))
        if value is None or value <= ZERO:
            raise StrategyEvaluationError(f"보호 규칙 평가에 필요한 포트폴리오 값이 없습니다: {key}")
        return value


class EvaluatorRegistry:
    def __init__(self) -> None:
        self._evaluators: dict[StrategyEngine, StrategyEvaluator] = {
            StrategyEngine.QQQM_DRAWDOWN_V2: QqqmDrawdownEvaluator(),
            StrategyEngine.RULE_ALLOCATION_V1: RuleAllocationEvaluator(),
            StrategyEngine.SIGNAL_TRADING_V1: SignalTradingEvaluator(),
        }

    def evaluate(self, version: StrategyVersion, context: EvaluationContext) -> EvaluationResult:
        try:
            evaluator = self._evaluators[version.definition.engine]
        except KeyError as exc:
            raise StrategyEvaluationError(
                f"지원하지 않는 전략 엔진입니다: {version.definition.engine}"
            ) from exc
        return evaluator.evaluate(version, context)
