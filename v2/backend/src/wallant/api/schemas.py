from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from wallant.domain.execution import RiskPolicy
from wallant.domain.portfolio import Portfolio
from wallant.domain.strategy import (
    EvaluationContext,
    StrategyDefinition,
    StrategyLifecycle,
)
from wallant.research.models import CorporateAction, MarketBar


class CreateStrategyRequest(BaseModel):
    definition: StrategyDefinition


class LifecycleTransitionRequest(BaseModel):
    target: StrategyLifecycle
    confirmed: bool = False
    evidence: dict[str, Any] = Field(default_factory=dict)


class EvaluateRequest(BaseModel):
    version_id: UUID
    context: EvaluationContext


class BacktestRequest(BaseModel):
    version_id: UUID
    bars: list[MarketBar]
    initial_cash: Decimal = Field(default=Decimal("100000"), gt=0)
    corporate_actions: list[CorporateAction] = Field(default_factory=list)


class RollingRequest(BacktestRequest):
    window_days: int = Field(default=252, ge=2)
    step_days: int = Field(default=63, ge=1)


class CreatePaperSessionRequest(BaseModel):
    version_id: UUID
    account_id: UUID


class CompletePaperSessionRequest(BaseModel):
    confirmed: bool
    note: str = Field(default="", max_length=1000)


class PaperStepRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    context: EvaluationContext
    portfolio: Portfolio
    quotes: dict[str, Decimal]


class CreateAccountRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=100)
    market: str = Field(pattern="^(US|KRX)$")
    currency: str = Field(pattern="^(USD|KRW)$")
    risk_policy: RiskPolicy


class AssignStrategyRequest(BaseModel):
    strategy_version_id: UUID


class PauseRequest(BaseModel):
    reason: str = Field(min_length=1, max_length=1000)


class ResumeRequest(BaseModel):
    confirmed: bool


class CredentialRequest(BaseModel):
    app_key: str = Field(min_length=1)
    app_secret: str = Field(min_length=1)
    account_number: str = Field(min_length=1)
    product_code: str = Field(min_length=1)


class StoreBarsRequest(BaseModel):
    market: str = Field(pattern="^(US|KRX)$")
    resolution: str = Field(pattern="^(1d|1m)$")
    provider: str = Field(min_length=1, max_length=100)
    official: bool
    bars: list[MarketBar] = Field(min_length=1)
    warning: str | None = Field(default=None, max_length=1000)

    @model_validator(mode="after")
    def validate_resolution(self) -> StoreBarsRequest:
        if self.resolution == "1m" and any(bar.observed_at is None for bar in self.bars):
            raise ValueError("분봉 데이터에는 observed_at 시각이 필요합니다.")
        return self


class DataCoverageQuery(BaseModel):
    symbol: str
    resolution: str
    start_date: date | None = None
    end_date: date | None = None
