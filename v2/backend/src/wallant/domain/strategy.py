from __future__ import annotations

import json
import re
from datetime import UTC, date, datetime
from decimal import Decimal
from enum import StrEnum
from hashlib import sha256
from typing import Any, Literal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator

from wallant.domain.money import HUNDRED, ZERO


class Market(StrEnum):
    KRX = "KRX"
    US = "US"


class Resolution(StrEnum):
    DAY = "1d"
    MINUTE = "1m"


class StrategyEngine(StrEnum):
    QQQM_DRAWDOWN_V2 = "QQQM_DRAWDOWN_V2"
    RULE_ALLOCATION_V1 = "RULE_ALLOCATION_V1"
    SIGNAL_TRADING_V1 = "SIGNAL_TRADING_V1"


class StrategyLifecycle(StrEnum):
    DRAFT = "DRAFT"
    BACKTESTED = "BACKTESTED"
    ROLLING_VALIDATED = "ROLLING_VALIDATED"
    PAPER = "PAPER"
    LIVE_APPROVED = "LIVE_APPROVED"
    RETIRED = "RETIRED"


class ConditionOperator(StrEnum):
    EQ = "EQ"
    NE = "NE"
    GT = "GT"
    GTE = "GTE"
    LT = "LT"
    LTE = "LTE"
    BETWEEN = "BETWEEN"


class UniverseDefinition(BaseModel):
    model_config = ConfigDict(extra="forbid")

    market: Market = Market.US
    symbols: list[str] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_universe(self) -> UniverseDefinition:
        self.symbols = sorted({symbol.strip().upper() for symbol in self.symbols if symbol.strip()})
        invalid = [symbol for symbol in self.symbols if not valid_symbol(symbol)]
        if invalid:
            raise ValueError(f"지원하지 않는 종목 코드 형식입니다: {', '.join(invalid)}")
        if not self.symbols:
            raise ValueError("고정 종목군에는 하나 이상의 종목이 필요합니다.")
        return self


class SignalKind(StrEnum):
    PRICE_MA_CROSS = "PRICE_MA_CROSS"
    MA_CROSS = "MA_CROSS"
    HIGH_BREAKOUT = "HIGH_BREAKOUT"
    RSI_RECOVERY = "RSI_RECOVERY"


class SignalTradingRules(BaseModel):
    kind: SignalKind
    ma_period: int = Field(default=200, ge=2, le=500)
    fast_period: int = Field(default=20, ge=2, le=500)
    slow_period: int = Field(default=50, ge=3, le=500)
    breakout_period: int = Field(default=20, ge=2, le=500)
    rsi_period: int = Field(default=14, ge=2, le=100)
    rsi_entry_threshold: Decimal = Field(default=Decimal("30"), gt=0, lt=100)
    rsi_exit_threshold: Decimal = Field(default=Decimal("70"), gt=0, lt=100)
    target_weight_pct: Decimal = Field(default=HUNDRED, gt=0, le=100)

    @model_validator(mode="after")
    def validate_periods_and_thresholds(self) -> SignalTradingRules:
        if self.fast_period >= self.slow_period:
            raise ValueError("단기 이동평균 기간은 장기 이동평균 기간보다 짧아야 합니다.")
        if self.rsi_entry_threshold >= self.rsi_exit_threshold:
            raise ValueError("RSI 진입 기준은 청산 기준보다 낮아야 합니다.")
        return self


class DataRequirement(BaseModel):
    key: str
    resolution: Resolution
    fields: list[str]
    official_only_for_live: bool = True


class Operand(BaseModel):
    source: Literal["market", "state", "portfolio", "constant"]
    key: str | None = None
    value: Decimal | str | bool | None = None

    @model_validator(mode="after")
    def validate_operand(self) -> Operand:
        if self.source == "constant" and self.value is None:
            raise ValueError("상수 피연산자에는 value가 필요합니다.")
        if self.source != "constant" and not self.key:
            raise ValueError("상수가 아닌 피연산자에는 key가 필요합니다.")
        return self


class Condition(BaseModel):
    left: Operand
    operator: ConditionOperator
    right: Operand
    upper: Operand | None = None

    @model_validator(mode="after")
    def validate_between(self) -> Condition:
        if self.operator == ConditionOperator.BETWEEN and self.upper is None:
            raise ValueError("BETWEEN 조건에는 upper가 필요합니다.")
        return self


class ConditionGroup(BaseModel):
    mode: Literal["ALL", "ANY"] = "ALL"
    conditions: list[Condition] = Field(min_length=1)


class AllocationRule(BaseModel):
    name: str
    priority: int = Field(ge=0)
    when: ConditionGroup
    target_weights: dict[str, Decimal]
    next_state: str | None = None

    @model_validator(mode="after")
    def validate_weights(self) -> AllocationRule:
        self.target_weights = {
            symbol.strip().upper(): Decimal(str(weight)) for symbol, weight in self.target_weights.items()
        }
        invalid = [symbol for symbol in self.target_weights if not valid_symbol(symbol)]
        if invalid:
            raise ValueError(f"지원하지 않는 목표 종목 코드 형식입니다: {', '.join(invalid)}")
        total = sum(self.target_weights.values(), ZERO)
        if total != HUNDRED:
            raise ValueError(f"목표 비중 합계는 100이어야 합니다: {total}")
        if any(weight < ZERO for weight in self.target_weights.values()):
            raise ValueError("목표 비중은 음수일 수 없습니다.")
        return self


class ProtectionRules(BaseModel):
    stop_loss_pct: Decimal | None = Field(default=None, gt=0, le=100)
    take_profit_pct: Decimal | None = Field(default=None, gt=0)
    trailing_stop_pct: Decimal | None = Field(default=None, gt=0, le=100)


class StrategyDefinition(BaseModel):
    model_config = ConfigDict(use_enum_values=False)

    name: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=1000)
    engine: StrategyEngine
    market: Market
    universe: UniverseDefinition
    signal_symbol: str
    schedule: str = "EOD"
    tolerance_pct: Decimal = Field(default=Decimal("5"), ge=0, le=100)
    parameters: dict[str, Any] = Field(default_factory=dict)
    data_requirements: list[DataRequirement] = Field(default_factory=list)
    rules: list[AllocationRule] = Field(default_factory=list)
    signal_rules: SignalTradingRules | None = None
    protections: ProtectionRules = Field(default_factory=ProtectionRules)

    @model_validator(mode="after")
    def validate_definition(self) -> StrategyDefinition:
        self.signal_symbol = self.signal_symbol.strip().upper()
        if not valid_symbol(self.signal_symbol):
            raise ValueError(f"지원하지 않는 신호 종목 코드 형식입니다: {self.signal_symbol}")
        if self.universe.market != self.market:
            raise ValueError("전략 시장과 종목군 시장은 같아야 합니다.")
        if self.signal_symbol not in self.universe.symbols:
            raise ValueError("신호 종목은 고정 종목군에 포함되어야 합니다.")
        if self.engine == StrategyEngine.QQQM_DRAWDOWN_V2:
            if self.market != Market.US or self.signal_symbol != "QQQM":
                raise ValueError("QQQM 낙폭 엔진은 미국 시장의 QQQM 신호만 지원합니다.")
            if set(self.universe.symbols) != {"QQQM", "QLD", "TQQQ"}:
                raise ValueError("QQQM 낙폭 엔진의 거래 종목군은 QQQM, QLD, TQQQ로 고정됩니다.")
        if self.engine == StrategyEngine.RULE_ALLOCATION_V1 and not self.rules:
            raise ValueError("규칙 기반 전략에는 하나 이상의 배분 규칙이 필요합니다.")
        if self.engine == StrategyEngine.SIGNAL_TRADING_V1:
            if len(self.universe.symbols) != 1:
                raise ValueError("개별종목 신호 전략은 한 종목만 지원합니다.")
            if self.signal_rules is None:
                raise ValueError("개별종목 신호 전략에는 매수·매도 신호 설정이 필요합니다.")
            if self.rules:
                raise ValueError("개별종목 신호 전략에는 배분 규칙을 함께 사용할 수 없습니다.")
        elif self.signal_rules is not None:
            raise ValueError("신호 설정은 개별종목 신호 전략에서만 사용할 수 있습니다.")
        if self.engine != StrategyEngine.SIGNAL_TRADING_V1 and self.protections != ProtectionRules():
            raise ValueError("손절·익절·트레일링은 개별종목 신호 전략에서만 사용할 수 있습니다.")
        if len({rule.priority for rule in self.rules}) != len(self.rules):
            raise ValueError("배분 규칙 우선순위는 중복될 수 없습니다.")
        outside = sorted(
            {
                symbol
                for rule in self.rules
                for symbol in rule.target_weights
                if symbol not in self.universe.symbols
            }
        )
        if outside:
            raise ValueError(f"목표 종목은 고정 종목군에 포함되어야 합니다: {', '.join(outside)}")
        return self

    def checksum(self) -> str:
        payload = json.dumps(
            self.model_dump(mode="json", exclude_none=False),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return sha256(payload.encode("utf-8")).hexdigest()


class StrategyVersion(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    strategy_id: UUID = Field(default_factory=uuid4)
    version: int = Field(default=1, ge=1)
    lifecycle: StrategyLifecycle = StrategyLifecycle.DRAFT
    definition: StrategyDefinition
    checksum: str = ""
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    approved_at: datetime | None = None

    @model_validator(mode="after")
    def fill_checksum(self) -> StrategyVersion:
        expected = self.definition.checksum()
        if self.checksum and self.checksum != expected:
            raise ValueError("전략 정의 체크섬이 일치하지 않습니다.")
        self.checksum = expected
        return self


class EvaluationContext(BaseModel):
    as_of: date
    market: dict[str, Decimal | str | bool | None]
    history: dict[str, list[Decimal]] = Field(default_factory=dict)
    previous_state: dict[str, Any] = Field(default_factory=dict)
    previous_target_weights: dict[str, Decimal] = Field(default_factory=dict)
    portfolio: dict[str, Decimal | str | bool] = Field(default_factory=dict)


class EvaluationResult(BaseModel):
    as_of: date
    strategy_version_id: UUID
    engine: StrategyEngine
    state: dict[str, Any]
    target_weights: dict[str, Decimal]
    base_target_weights: dict[str, Decimal]
    events: list[str] = Field(default_factory=list)
    explanation: list[str] = Field(default_factory=list)


def valid_symbol(symbol: str) -> bool:
    return bool(re.fullmatch(r"[A-Z0-9][A-Z0-9._^-]{0,19}", symbol))
