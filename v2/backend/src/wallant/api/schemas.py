from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from wallant.domain.execution import ExecutionProfile, RiskPolicy
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
    account_id: UUID | None = None


class CompletePaperSessionRequest(BaseModel):
    confirmed: bool
    note: str = Field(default="", max_length=1000)


class PaperStepRequest(BaseModel):
    context: EvaluationContext
    portfolio: Portfolio
    quotes: dict[str, Decimal]
    risk_policy: RiskPolicy


class CreateAccountRequest(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    market: str = Field(pattern="^(US|KRX)$")
    currency: str = Field(pattern="^(USD|KRW)$")
    execution_profile: ExecutionProfile
    risk_policy: RiskPolicy

    @model_validator(mode="after")
    def validate_reprice_limit(self) -> CreateAccountRequest:
        if self.execution_profile.max_reprice_attempts > self.risk_policy.max_reprice_attempts:
            raise ValueError("실행 프로필의 재가격 횟수는 계좌 위험 한도를 넘을 수 없습니다.")
        return self


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


class LegacyMigrationPreviewRequest(BaseModel):
    destination_account_id: UUID
    source_database_url: str
    include_strategy_state: bool = True
    include_portfolio_snapshots: bool = True
    include_performance_snapshots: bool = True
    include_jobs_and_orders: bool = True


class DataCoverageQuery(BaseModel):
    symbol: str
    resolution: str
    start_date: date | None = None
    end_date: date | None = None
