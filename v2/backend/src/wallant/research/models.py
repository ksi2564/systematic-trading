from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from uuid import UUID, uuid4

from pydantic import BaseModel, Field, model_validator

from wallant.domain.strategy import EvaluationResult


class CorporateActionKind(StrEnum):
    CASH_DIVIDEND = "CASH_DIVIDEND"
    SPLIT = "SPLIT"


class MarketBar(BaseModel):
    trading_date: date
    observed_at: datetime | None = None
    symbol: str
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: Decimal = Decimal("0")
    available_at: date | None = None
    provider: str = "fixture"
    official: bool = False

    @model_validator(mode="after")
    def normalize(self) -> MarketBar:
        self.symbol = self.symbol.strip().upper()
        if not re.fullmatch(r"[A-Z0-9][A-Z0-9._^-]{0,19}", self.symbol):
            raise ValueError(f"지원하지 않는 종목 코드 형식입니다: {self.symbol}")
        if min(self.open, self.high, self.low, self.close) <= 0:
            raise ValueError("OHLC 가격은 양수여야 합니다.")
        if self.low > self.high:
            raise ValueError("저가는 고가보다 클 수 없습니다.")
        if self.low > min(self.open, self.close) or self.high < max(self.open, self.close):
            raise ValueError("시가와 종가는 저가·고가 범위 안에 있어야 합니다.")
        if self.volume < 0:
            raise ValueError("거래량은 음수일 수 없습니다.")
        self.available_at = self.available_at or self.trading_date
        return self


class CorporateAction(BaseModel):
    action_date: date
    symbol: str
    kind: CorporateActionKind
    value: Decimal = Field(gt=0)
    available_at: date | None = None

    @model_validator(mode="after")
    def normalize(self) -> CorporateAction:
        self.symbol = self.symbol.strip().upper()
        self.available_at = self.available_at or self.action_date
        return self


class BacktestTrade(BaseModel):
    trading_date: date
    signal_date: date
    symbol: str
    side: str
    quantity: int
    price: Decimal
    fee: Decimal
    reason: str


class EquityPoint(BaseModel):
    trading_date: date
    equity: Decimal
    cash: Decimal
    drawdown_pct: Decimal
    target_weights: dict[str, Decimal]


class BacktestMetrics(BaseModel):
    initial_equity: Decimal
    final_equity: Decimal
    total_return_pct: Decimal
    max_drawdown_pct: Decimal
    trade_count: int
    evaluated_days: int
    start_date: date
    end_date: date


class BacktestResult(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    strategy_version_id: UUID
    metrics: BacktestMetrics
    equity_curve: list[EquityPoint]
    trades: list[BacktestTrade]
    evaluations: list[EvaluationResult]
    warnings: list[str] = Field(default_factory=list)


class RollingWindowResult(BaseModel):
    window: int
    start_date: date
    end_date: date
    metrics: BacktestMetrics


class RollingValidationResult(BaseModel):
    strategy_version_id: UUID
    fixed_parameters: bool = True
    windows: list[RollingWindowResult]
    median_return_pct: Decimal
    worst_return_pct: Decimal
    worst_drawdown_pct: Decimal
    warnings: list[str] = Field(default_factory=list)
