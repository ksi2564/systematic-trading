from __future__ import annotations

import json
from datetime import date
from decimal import ROUND_HALF_UP, Decimal, localcontext
from enum import StrEnum
from hashlib import sha256
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

ZERO = Decimal("0")
HUNDRED = Decimal("100")
MAX_DECIMAL_DIGITS = 64
MAX_DECIMAL_EXPONENT = 24
DECISION_PCT_SCALE = Decimal("0.000000000000000000000001")


class EodStateError(ValueError):
    """EOD 상태를 안전하게 계산할 수 없을 때 발생한다."""


class BootstrapRequired(EodStateError):
    """Python이 소유한 직전 EOD 상태가 없을 때 발생한다."""


class EodPhase(StrEnum):
    NORMAL = "NORMAL"
    DRAWDOWN = "DRAWDOWN"
    RECOVERY = "RECOVERY"


class DrawdownBucket(StrEnum):
    LESS_THAN_15 = "LESS_THAN_15"
    FROM_15_TO_25 = "FROM_15_TO_25"
    FROM_25_TO_35 = "FROM_25_TO_35"
    FROM_35_TO_45 = "FROM_35_TO_45"
    AT_LEAST_45 = "AT_LEAST_45"


class BaseTargetWeights(BaseModel):
    """EOD가 만드는 QQQM 전략의 기준 비중이다."""

    model_config = ConfigDict(
        allow_inf_nan=False,
        extra="forbid",
        frozen=True,
        revalidate_instances="always",
    )

    QQQM: Decimal = Field(ge=0, le=100)
    QLD: Decimal = Field(ge=0, le=100)
    TQQQ: Decimal = Field(ge=0, le=100)

    @field_validator("QQQM", "QLD", "TQQQ", mode="before")
    @classmethod
    def validate_decimal_shape(cls, value: object) -> Decimal:
        return _bounded_decimal(value)

    @field_validator("QQQM", "QLD", "TQQQ")
    @classmethod
    def normalize_decimal(cls, value: Decimal) -> Decimal:
        return _normalize_decimal(value)

    @model_validator(mode="after")
    def validate_total(self) -> BaseTargetWeights:
        if self.QQQM + self.QLD + self.TQQQ != HUNDRED:
            raise ValueError("QQQM/QLD/TQQQ 기준 비중의 합은 100이어야 합니다.")
        return self


class EodState(BaseModel):
    """확정 종가만으로 계산한 Python 소유 EOD 상태다."""

    model_config = ConfigDict(
        allow_inf_nan=False,
        extra="forbid",
        frozen=True,
        revalidate_instances="always",
    )

    contract_version: Literal["PYTHON_QQQM_EOD_V1"] = "PYTHON_QQQM_EOD_V1"
    market_date: date
    ath: Decimal = Field(gt=0)
    qqqm_close: Decimal = Field(gt=0)
    drawdown_pct: Decimal = Field(ge=0, le=100)
    max_drawdown_pct_since_ath: Decimal = Field(ge=0, le=100)
    drawdown_bucket: DrawdownBucket
    phase: EodPhase
    base_target_weights: BaseTargetWeights
    explanation: tuple[str, ...] = Field(min_length=1)

    @field_validator(
        "ath",
        "qqqm_close",
        "drawdown_pct",
        "max_drawdown_pct_since_ath",
        mode="before",
    )
    @classmethod
    def validate_decimal_shape(cls, value: object) -> Decimal:
        return _bounded_decimal(value)

    @field_validator(
        "ath",
        "qqqm_close",
        "drawdown_pct",
        "max_drawdown_pct_since_ath",
    )
    @classmethod
    def normalize_decimal(cls, value: Decimal) -> Decimal:
        return _normalize_decimal(value)

    @model_validator(mode="after")
    def validate_consistency(self) -> EodState:
        if self.ath < self.qqqm_close:
            raise ValueError("ATH는 QQQM 종가보다 작을 수 없습니다.")
        if self.max_drawdown_pct_since_ath < self.drawdown_pct:
            raise ValueError("ATH 이후 최대 낙폭은 현재 낙폭보다 작을 수 없습니다.")
        expected_drawdown = QqqmEodStateCalculator._drawdown(self.ath, self.qqqm_close)
        if self.drawdown_pct != expected_drawdown:
            raise ValueError("저장된 현재 낙폭이 ATH와 QQQM 종가로 계산한 값과 다릅니다.")
        if self.drawdown_bucket is not QqqmEodStateCalculator._bucket(self.drawdown_pct):
            raise ValueError("저장된 낙폭 구간이 현재 낙폭과 다릅니다.")
        expected_phase = QqqmEodStateCalculator._phase(
            self.max_drawdown_pct_since_ath,
            self.drawdown_pct,
        )
        if self.phase is not expected_phase:
            raise ValueError("저장된 단계가 현재·최대 낙폭과 다릅니다.")
        if not QqqmEodStateCalculator._valid_weights_for_state(
            self.phase,
            self.drawdown_bucket,
            self.drawdown_pct,
            self.max_drawdown_pct_since_ath,
            self.base_target_weights,
        ):
            raise ValueError("저장된 기준 비중이 낙폭 단계의 승인 비중과 다릅니다.")
        return self

    def checksum(self) -> str:
        payload = json.dumps(
            self.model_dump(mode="json", exclude={"explanation"}),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return sha256(payload.encode("utf-8")).hexdigest()


class EodStateRequest(BaseModel):
    """EOD 계산에 허용되는 입력만 명시한다.

    VIX, MA200, 계좌, 호가와 주문 관련 값은 다음 거래일 판단의 입력이므로
    이 모델에 들어올 수 없다.
    """

    model_config = ConfigDict(
        allow_inf_nan=False,
        extra="forbid",
        frozen=True,
        revalidate_instances="always",
    )

    market_date: date
    qqqm_close: Decimal = Field(gt=0)
    previous_state: EodState | None = None

    @field_validator("qqqm_close", mode="before")
    @classmethod
    def validate_decimal_shape(cls, value: object) -> Decimal:
        return _bounded_decimal(value)

    @field_validator("qqqm_close")
    @classmethod
    def normalize_decimal(cls, value: Decimal) -> Decimal:
        return _normalize_decimal(value)

    @model_validator(mode="after")
    def validate_market_date_order(self) -> EodStateRequest:
        if self.previous_state and self.previous_state.market_date >= self.market_date:
            raise ValueError("새 EOD 시장일은 직전 EOD 시장일보다 뒤여야 합니다.")
        return self


class QqqmEodStateCalculator:
    """외부 입출력 없이 QQQM EOD 상태 전이만 계산한다."""

    RECOVERY_ACTIVATION = Decimal("15")
    RECOVERY_DRAWDOWN = Decimal("10")

    def calculate(self, request: EodStateRequest) -> EodState:
        previous = request.previous_state
        if previous is None:
            raise BootstrapRequired(
                "Python 직전 EOD 상태가 없습니다. 초기 상태 생성 정책의 승인이 필요합니다."
            )

        close = request.qqqm_close
        if close > previous.ath:
            ath = close
            drawdown = ZERO
            max_drawdown = ZERO
        else:
            ath = previous.ath
            drawdown = self._drawdown(ath, close)
            max_drawdown = max(previous.max_drawdown_pct_since_ath, drawdown)

        bucket = self._bucket(drawdown)
        phase = self._phase(max_drawdown, drawdown)
        weights = self._weights(phase, bucket, drawdown, previous.base_target_weights)
        explanation = (
            f"{request.market_date.isoformat()} QQQM 확정 종가 {_plain_decimal(close)}",
            "ATH "
            f"{_plain_decimal(ath)} 대비 현재 낙폭 {_plain_decimal(self._display_pct(drawdown))}%, "
            f"최대 낙폭 {_plain_decimal(self._display_pct(max_drawdown))}%",
            "EOD 기준 비중 "
            f"QQQM {_plain_decimal(weights.QQQM)}% / "
            f"QLD {_plain_decimal(weights.QLD)}% / "
            f"TQQQ {_plain_decimal(weights.TQQQ)}%",
        )
        return EodState(
            market_date=request.market_date,
            ath=ath,
            qqqm_close=close,
            drawdown_pct=drawdown,
            max_drawdown_pct_since_ath=max_drawdown,
            drawdown_bucket=bucket,
            phase=phase,
            base_target_weights=weights,
            explanation=explanation,
        )

    @classmethod
    def _phase(cls, max_drawdown: Decimal, drawdown: Decimal) -> EodPhase:
        if max_drawdown < cls.RECOVERY_ACTIVATION:
            return EodPhase.NORMAL
        if drawdown <= cls.RECOVERY_DRAWDOWN:
            return EodPhase.RECOVERY
        return EodPhase.DRAWDOWN

    @staticmethod
    def _bucket(drawdown: Decimal) -> DrawdownBucket:
        if drawdown < Decimal("15"):
            return DrawdownBucket.LESS_THAN_15
        if drawdown < Decimal("25"):
            return DrawdownBucket.FROM_15_TO_25
        if drawdown < Decimal("35"):
            return DrawdownBucket.FROM_25_TO_35
        if drawdown < Decimal("45"):
            return DrawdownBucket.FROM_35_TO_45
        return DrawdownBucket.AT_LEAST_45

    @classmethod
    def _weights(
        cls,
        phase: EodPhase,
        bucket: DrawdownBucket,
        drawdown: Decimal,
        previous_weights: BaseTargetWeights,
    ) -> BaseTargetWeights:
        if phase is EodPhase.NORMAL:
            return cls._allocation(100, 0, 0)
        if phase is EodPhase.RECOVERY:
            return cls._allocation(70, 30, 0)
        if cls.RECOVERY_DRAWDOWN < drawdown < cls.RECOVERY_ACTIVATION:
            return previous_weights
        return cls._bucket_allocation(bucket)

    @classmethod
    def _bucket_allocation(cls, bucket: DrawdownBucket) -> BaseTargetWeights:
        return {
            DrawdownBucket.LESS_THAN_15: cls._allocation(100, 0, 0),
            DrawdownBucket.FROM_15_TO_25: cls._allocation(60, 30, 10),
            DrawdownBucket.FROM_25_TO_35: cls._allocation(40, 40, 20),
            DrawdownBucket.FROM_35_TO_45: cls._allocation(30, 30, 40),
            DrawdownBucket.AT_LEAST_45: cls._allocation(20, 20, 60),
        }[bucket]

    @classmethod
    def _valid_weights_for_state(
        cls,
        phase: EodPhase,
        bucket: DrawdownBucket,
        drawdown: Decimal,
        max_drawdown: Decimal,
        weights: BaseTargetWeights,
    ) -> bool:
        if phase is EodPhase.NORMAL:
            return weights == cls._allocation(100, 0, 0)
        if phase is EodPhase.RECOVERY:
            return weights == cls._allocation(70, 30, 0)
        if cls.RECOVERY_DRAWDOWN < drawdown < cls.RECOVERY_ACTIVATION:
            carryable = [
                cls._allocation(70, 30, 0),
                cls._allocation(60, 30, 10),
            ]
            if max_drawdown >= Decimal("25"):
                carryable.append(cls._allocation(40, 40, 20))
            if max_drawdown >= Decimal("35"):
                carryable.append(cls._allocation(30, 30, 40))
            if max_drawdown >= Decimal("45"):
                carryable.append(cls._allocation(20, 20, 60))
            return weights in carryable
        return weights == cls._bucket_allocation(bucket)

    @staticmethod
    def _allocation(qqqm: int, qld: int, tqqq: int) -> BaseTargetWeights:
        return BaseTargetWeights(
            QQQM=Decimal(qqqm).quantize(Decimal("0.00")),
            QLD=Decimal(qld).quantize(Decimal("0.00")),
            TQQQ=Decimal(tqqq).quantize(Decimal("0.00")),
        )

    @classmethod
    def _drawdown(cls, ath: Decimal, close: Decimal) -> Decimal:
        precision = max(50, len(ath.as_tuple().digits), len(close.as_tuple().digits)) * 2
        with localcontext() as context:
            context.prec = precision
            drawdown = (ath - close) / ath * HUNDRED
            return _normalize_decimal(
                drawdown.quantize(DECISION_PCT_SCALE, rounding=ROUND_HALF_UP)
            )

    @staticmethod
    def _display_pct(value: Decimal) -> Decimal:
        return value.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def _normalize_decimal(value: Decimal) -> Decimal:
    if value == ZERO:
        return ZERO
    plain = format(value, "f")
    if "." in plain:
        plain = plain.rstrip("0").rstrip(".")
    return Decimal(plain)


def _plain_decimal(value: Decimal) -> str:
    return format(_normalize_decimal(value), "f")


def _bounded_decimal(value: object) -> Decimal:
    try:
        parsed = value if isinstance(value, Decimal) else Decimal(str(value))
    except (ValueError, TypeError) as exc:
        raise ValueError("숫자로 변환할 수 없는 값입니다.") from exc
    if not parsed.is_finite():
        raise ValueError("유한한 숫자만 사용할 수 있습니다.")
    decimal_tuple = parsed.as_tuple()
    if len(decimal_tuple.digits) > MAX_DECIMAL_DIGITS:
        raise ValueError(f"숫자는 유효 숫자 {MAX_DECIMAL_DIGITS}자 이하여야 합니다.")
    if abs(decimal_tuple.exponent) > MAX_DECIMAL_EXPONENT:
        raise ValueError(f"숫자의 소수 지수 절댓값은 {MAX_DECIMAL_EXPONENT} 이하여야 합니다.")
    return parsed
