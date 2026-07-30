from __future__ import annotations

from decimal import Decimal
from statistics import median

from wallant.domain.strategy import StrategyVersion
from wallant.research.backtest import BacktestEngine
from wallant.research.models import (
    CorporateAction,
    MarketBar,
    RollingValidationResult,
    RollingWindowResult,
)


class RollingValidationService:
    def __init__(self, backtest: BacktestEngine | None = None) -> None:
        self.backtest = backtest or BacktestEngine()

    def run(
        self,
        version: StrategyVersion,
        bars: list[MarketBar],
        *,
        window_days: int,
        step_days: int,
        initial_cash: Decimal = Decimal("100000"),
        corporate_actions: list[CorporateAction] | None = None,
    ) -> RollingValidationResult:
        if window_days < 2 or step_days < 1:
            raise ValueError("롤링 창은 2일 이상, 이동 간격은 1일 이상이어야 합니다.")
        dates = sorted({bar.trading_date for bar in bars if bar.symbol == version.definition.signal_symbol})
        if len(dates) < window_days:
            raise ValueError("롤링 검증 창보다 데이터 기간이 짧습니다.")

        windows: list[RollingWindowResult] = []
        warnings: list[str] = []
        for index, start in enumerate(range(0, len(dates) - window_days + 1, step_days), start=1):
            selected_dates = set(dates[start : start + window_days])
            selected_bars = [bar for bar in bars if bar.trading_date in selected_dates]
            selected_actions = [
                action for action in corporate_actions or [] if action.action_date in selected_dates
            ]
            result = self.backtest.run(
                version,
                selected_bars,
                initial_cash=initial_cash,
                corporate_actions=selected_actions,
            )
            warnings.extend(result.warnings)
            windows.append(
                RollingWindowResult(
                    window=index,
                    start_date=result.metrics.start_date,
                    end_date=result.metrics.end_date,
                    metrics=result.metrics,
                )
            )
        returns = [window.metrics.total_return_pct for window in windows]
        drawdowns = [window.metrics.max_drawdown_pct for window in windows]
        return RollingValidationResult(
            strategy_version_id=version.id,
            windows=windows,
            median_return_pct=Decimal(str(median(returns))),
            worst_return_pct=min(returns),
            worst_drawdown_pct=max(drawdowns),
            warnings=list(dict.fromkeys(warnings)),
        )
