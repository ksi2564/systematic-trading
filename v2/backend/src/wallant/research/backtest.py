from __future__ import annotations

from collections import defaultdict
from datetime import date
from decimal import ROUND_DOWN, Decimal

from wallant.domain.evaluator import EvaluatorRegistry
from wallant.domain.money import HUNDRED, ZERO, decimal, money, pct
from wallant.domain.strategy import EvaluationContext, StrategyEngine, StrategyVersion
from wallant.research.models import (
    BacktestMetrics,
    BacktestResult,
    BacktestTrade,
    CorporateAction,
    CorporateActionKind,
    EquityPoint,
    MarketBar,
)


class BacktestEngine:
    """종가 평가 후 다음 거래일 시가 체결로 미래 참조를 피한다."""

    def __init__(self, registry: EvaluatorRegistry | None = None) -> None:
        self.registry = registry or EvaluatorRegistry()

    def run(
        self,
        version: StrategyVersion,
        bars: list[MarketBar],
        *,
        initial_cash: Decimal = Decimal("100000"),
        corporate_actions: list[CorporateAction] | None = None,
        evaluation_start_date: date | None = None,
    ) -> BacktestResult:
        if not bars:
            raise ValueError("백테스트 가격 데이터가 없습니다.")
        warnings: list[str] = []
        by_date: dict[date, dict[str, MarketBar]] = defaultdict(dict)
        seen: set[tuple[date, str]] = set()
        excluded_late_bars = 0
        for bar in sorted(bars, key=lambda item: (item.trading_date, item.symbol)):
            key = (bar.trading_date, bar.symbol)
            if key in seen:
                raise ValueError(f"중복 가격 행이 있습니다: {bar.trading_date} {bar.symbol}")
            seen.add(key)
            if bar.available_at and bar.available_at > bar.trading_date:
                excluded_late_bars += 1
                continue
            by_date[bar.trading_date][bar.symbol] = bar
        action_by_date: dict[date, list[CorporateAction]] = defaultdict(list)
        excluded_late_actions = 0
        for action in corporate_actions or []:
            if action.available_at and action.available_at > action.action_date:
                excluded_late_actions += 1
                continue
            action_by_date[action.action_date].append(action)
        unofficial_providers = sorted({bar.provider for bar in bars if not bar.official})
        if unofficial_providers:
            warnings.append("비공식 연구 데이터가 포함되었습니다: " + ", ".join(unofficial_providers))
        if excluded_late_bars:
            warnings.append(
                f"평가일 뒤에 공개된 가격 행 {excluded_late_bars}건을 미래 참조 방지를 위해 제외했습니다."
            )
        if excluded_late_actions:
            warnings.append(
                f"행사일 뒤에 공개된 기업행사 {excluded_late_actions}건을 미래 참조 방지를 위해 제외했습니다."
            )

        signal = version.definition.signal_symbol
        symbols = version.definition.universe.symbols
        try:
            ma_period = int(version.definition.parameters.get("ma_period", 200))
        except (TypeError, ValueError) as exc:
            raise ValueError("이동평균 기간은 1 이상의 정수여야 합니다.") from exc
        if ma_period < 1:
            raise ValueError("이동평균 기간은 1 이상의 정수여야 합니다.")
        required_rule_market_keys = {
            operand.key
            for rule in version.definition.rules
            for condition in rule.when.conditions
            for operand in (condition.left, condition.right, condition.upper)
            if operand is not None and operand.source == "market" and operand.key
        }
        moving_average_key = f"{signal}.ma_{ma_period}"
        requires_moving_average = moving_average_key in required_rule_market_keys
        trading_dates = sorted(by_date)
        if not any(signal in values for values in by_date.values()):
            raise ValueError(f"신호 종목 {signal} 데이터가 없습니다.")

        cash = decimal(initial_cash)
        holdings: dict[str, Decimal] = {symbol: ZERO for symbol in symbols}
        average_prices: dict[str, Decimal] = {symbol: ZERO for symbol in symbols}
        histories: dict[str, list[Decimal]] = defaultdict(list)
        previous_state: dict = {}
        previous_target: dict[str, Decimal] = {}
        pending_target: tuple[date, dict[str, Decimal]] | None = None
        evaluations = []
        trades: list[BacktestTrade] = []
        equity_curve: list[EquityPoint] = []
        high_watermark = cash
        fee_rate = decimal(version.definition.parameters.get("fee_rate_pct"), Decimal("0.25")) / HUNDRED
        warmup_skipped = 0
        missing_vix_days = 0
        missing_ma_guard_days = 0
        deferred_actions: list[CorporateAction] = []

        for trading_date in trading_dates:
            daily = by_date[trading_date]
            close_prices = {symbol: daily[symbol].close for symbol in symbols if symbol in daily}
            missing = [symbol for symbol in symbols if symbol not in close_prices]
            if missing:
                warnings.append(
                    f"{trading_date}: 필수 가격이 누락되어 주문과 평가를 보류합니다: {', '.join(missing)}"
                )
                deferred_actions.extend(action_by_date[trading_date])
                self._append_available_history(histories, daily)
                continue
            if evaluation_start_date is not None and trading_date < evaluation_start_date:
                self._append_available_history(histories, daily)
                continue

            actions = [*deferred_actions, *action_by_date[trading_date]]
            deferred_actions = []
            self._apply_actions(actions, holdings, average_prices, cash_holder := [cash])
            cash = cash_holder[0]

            if pending_target is not None:
                signal_date, target = pending_target
                cash, generated = self._rebalance_at_open(
                    trading_date=trading_date,
                    signal_date=signal_date,
                    target=target,
                    daily=daily,
                    cash=cash,
                    holdings=holdings,
                    average_prices=average_prices,
                    fee_rate=fee_rate,
                    tolerance_pct=version.definition.tolerance_pct,
                )
                trades.extend(generated)
                pending_target = None

            signal_history = histories[signal]
            moving_values = [*signal_history, close_prices[signal]][-ma_period:]
            moving_average = (
                sum(moving_values, ZERO) / len(moving_values)
                if len(moving_values) >= ma_period
                else None
            )
            if requires_moving_average and moving_average is None:
                warmup_skipped += 1
                self._append_available_history(histories, daily)
                continue
            signal_warmup = self._signal_warmup_observations(version)
            if signal_warmup and len(signal_history) < signal_warmup:
                warmup_skipped += 1
                self._append_available_history(histories, daily)
                continue
            if version.definition.engine == StrategyEngine.QQQM_DRAWDOWN_V2:
                if (
                    bool(version.definition.parameters.get("circuit_breaker_enabled", True))
                    and moving_average is None
                ):
                    missing_ma_guard_days += 1
                if bool(version.definition.parameters.get("vix_enabled", True)) and "VIX" not in daily:
                    missing_vix_days += 1
            market: dict[str, Decimal | str | bool | None] = {
                **{f"{symbol}.close": price for symbol, price in close_prices.items()},
                "signal_close": close_prices[signal],
                "signal_ma_200": moving_average,
                moving_average_key: moving_average,
                "vix": daily["VIX"].close if "VIX" in daily else None,
            }
            portfolio_value = cash + sum(
                (holdings[symbol] * close_prices[symbol] for symbol in symbols),
                ZERO,
            )
            portfolio_payload = {
                "cash": cash,
                "total_value": portfolio_value,
            }
            if version.definition.engine == StrategyEngine.SIGNAL_TRADING_V1:
                quantity = holdings.get(signal, ZERO)
                portfolio_payload.update(
                    {
                        "position_open": quantity > ZERO,
                        "quantity": quantity,
                        "average_price": average_prices.get(signal, ZERO),
                    }
                )
            evaluation = self.registry.evaluate(
                version,
                EvaluationContext(
                    as_of=trading_date,
                    market=market,
                    history={key: list(values) for key, values in histories.items()},
                    previous_state=previous_state,
                    previous_target_weights=previous_target,
                    portfolio=portfolio_payload,
                ),
            )
            evaluations.append(evaluation)
            previous_state = evaluation.state
            previous_target = evaluation.target_weights
            if bool(evaluation.state.get("strategy_on", True)):
                pending_target = (trading_date, evaluation.target_weights)

            high_watermark = max(high_watermark, portfolio_value)
            drawdown = (
                pct((high_watermark - portfolio_value) / high_watermark * HUNDRED)
                if high_watermark > ZERO
                else ZERO
            )
            equity_curve.append(
                EquityPoint(
                    trading_date=trading_date,
                    equity=money(portfolio_value),
                    cash=money(cash),
                    drawdown_pct=drawdown,
                    target_weights=evaluation.target_weights,
                )
            )
            self._append_available_history(histories, daily)

        if warmup_skipped:
            warmup_label = (
                "신호 지표"
                if version.definition.engine == StrategyEngine.SIGNAL_TRADING_V1
                else f"필수 MA{ma_period}"
            )
            warnings.append(
                f"{warmup_label} 계산을 위해 첫 {warmup_skipped}개 평가일을 워밍업으로 제외했습니다."
            )
        if missing_ma_guard_days:
            warnings.append(
                f"MA{ma_period} 관측이 부족한 {missing_ma_guard_days}개 평가일에는 "
                "QQQM 이동평균 보호 필터를 적용하지 못했습니다."
            )
        if missing_vix_days:
            warnings.append(
                f"VIX가 없는 {missing_vix_days}개 평가일에는 변동성 보호 필터를 적용하지 못했습니다."
            )
        if not equity_curve:
            if warmup_skipped:
                raise ValueError(
                    f"필수 MA{ma_period} 워밍업 뒤 평가할 데이터가 없습니다. "
                    f"최소 {ma_period}개 이상의 연속 관측치를 제공하세요."
                )
            raise ValueError("평가 가능한 거래일이 없습니다.")
        final = equity_curve[-1].equity
        initial = money(initial_cash)
        total_return = pct((final - initial) / initial * HUNDRED) if initial > ZERO else ZERO
        metrics = BacktestMetrics(
            initial_equity=initial,
            final_equity=final,
            total_return_pct=total_return,
            max_drawdown_pct=max((point.drawdown_pct for point in equity_curve), default=ZERO),
            trade_count=len(trades),
            evaluated_days=len(equity_curve),
            start_date=equity_curve[0].trading_date,
            end_date=equity_curve[-1].trading_date,
        )
        if pending_target:
            warnings.append("마지막 평가일의 목표 비중은 다음 거래일 데이터가 없어 체결하지 않았습니다.")
        if deferred_actions:
            warnings.append("필수 가격 누락 뒤 적용할 거래일이 없어 일부 기업행사를 반영하지 못했습니다.")
        return BacktestResult(
            strategy_version_id=version.id,
            metrics=metrics,
            equity_curve=equity_curve,
            trades=trades,
            evaluations=evaluations,
            warnings=list(dict.fromkeys(warnings)),
        )

    def _rebalance_at_open(
        self,
        *,
        trading_date: date,
        signal_date: date,
        target: dict[str, Decimal],
        daily: dict[str, MarketBar],
        cash: Decimal,
        holdings: dict[str, Decimal],
        average_prices: dict[str, Decimal],
        fee_rate: Decimal,
        tolerance_pct: Decimal,
    ) -> tuple[Decimal, list[BacktestTrade]]:
        symbols = [symbol for symbol in target if symbol in daily]
        total = cash + sum((holdings.get(symbol, ZERO) * daily[symbol].open for symbol in symbols), ZERO)
        desired: dict[str, Decimal] = {}
        for symbol in symbols:
            current_weight = (
                holdings.get(symbol, ZERO) * daily[symbol].open / total * HUNDRED
                if total > ZERO
                else ZERO
            )
            if abs(decimal(target[symbol]) - current_weight) < tolerance_pct:
                desired[symbol] = holdings.get(symbol, ZERO)
            else:
                desired[symbol] = (
                    total * decimal(target[symbol]) / HUNDRED / daily[symbol].open
                ).quantize(Decimal("1"), rounding=ROUND_DOWN)
        trades: list[BacktestTrade] = []

        for symbol in sorted(
            symbols, key=lambda item: holdings.get(item, ZERO) - desired[item], reverse=True
        ):
            quantity = holdings.get(symbol, ZERO) - desired[symbol]
            if quantity <= ZERO:
                continue
            price = daily[symbol].open
            notional = price * quantity
            fee = money(notional * fee_rate)
            cash += notional - fee
            holdings[symbol] = desired[symbol]
            if holdings[symbol] == ZERO:
                average_prices[symbol] = ZERO
            trades.append(
                BacktestTrade(
                    trading_date=trading_date,
                    signal_date=signal_date,
                    symbol=symbol,
                    side="SELL",
                    quantity=int(quantity),
                    price=money(price),
                    fee=fee,
                    reason=f"목표 비중 {target[symbol]}%",
                )
            )

        for symbol in sorted(
            symbols, key=lambda item: desired[item] - holdings.get(item, ZERO), reverse=True
        ):
            quantity = desired[symbol] - holdings.get(symbol, ZERO)
            if quantity <= ZERO:
                continue
            price = daily[symbol].open
            unit_cost = price * (Decimal("1") + fee_rate)
            affordable = (cash / unit_cost).quantize(Decimal("1"), rounding=ROUND_DOWN)
            quantity = min(quantity, affordable)
            if quantity <= ZERO:
                continue
            notional = price * quantity
            fee = money(notional * fee_rate)
            previous_quantity = holdings.get(symbol, ZERO)
            previous_cost = average_prices.get(symbol, ZERO) * previous_quantity
            cash -= notional + fee
            holdings[symbol] = previous_quantity + quantity
            average_prices[symbol] = (
                (previous_cost + notional) / holdings[symbol] if holdings[symbol] > ZERO else ZERO
            )
            trades.append(
                BacktestTrade(
                    trading_date=trading_date,
                    signal_date=signal_date,
                    symbol=symbol,
                    side="BUY",
                    quantity=int(quantity),
                    price=money(price),
                    fee=fee,
                    reason=f"목표 비중 {target[symbol]}%",
                )
            )
        return cash, trades

    @staticmethod
    def _signal_warmup_observations(version: StrategyVersion) -> int:
        if version.definition.engine != StrategyEngine.SIGNAL_TRADING_V1:
            return 0
        rules = version.definition.signal_rules
        if rules is None:
            return 0
        if rules.kind.value == "PRICE_MA_CROSS":
            return rules.ma_period
        if rules.kind.value == "MA_CROSS":
            return rules.slow_period
        if rules.kind.value == "HIGH_BREAKOUT":
            return rules.breakout_period
        return rules.rsi_period + 1

    @staticmethod
    def _apply_actions(
        actions: list[CorporateAction],
        holdings: dict[str, Decimal],
        average_prices: dict[str, Decimal],
        cash_holder: list[Decimal],
    ) -> None:
        for action in actions:
            quantity = holdings.get(action.symbol, ZERO)
            if quantity <= ZERO:
                continue
            if action.kind == CorporateActionKind.CASH_DIVIDEND:
                cash_holder[0] += quantity * action.value
            elif action.kind == CorporateActionKind.SPLIT:
                holdings[action.symbol] = quantity * action.value
                average_prices[action.symbol] = average_prices.get(action.symbol, ZERO) / action.value

    @staticmethod
    def _append_available_history(
        histories: dict[str, list[Decimal]],
        daily: dict[str, MarketBar],
    ) -> None:
        for symbol, bar in daily.items():
            histories[symbol].append(bar.close)
