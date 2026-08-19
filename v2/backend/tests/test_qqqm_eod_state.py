from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError

from wallant.domain.qqqm_eod import (
    BaseTargetWeights,
    BootstrapRequired,
    DrawdownBucket,
    EodPhase,
    EodState,
    EodStateRequest,
    QqqmEodStateCalculator,
)


def previous_state(
    *,
    close: str = "80",
    drawdown: str = "20",
    max_drawdown: str = "20",
    phase: EodPhase = EodPhase.DRAWDOWN,
    weights: tuple[str, str, str] = ("60", "30", "10"),
) -> EodState:
    return EodState(
        market_date=date(2026, 8, 17),
        ath=Decimal("100"),
        qqqm_close=Decimal(close),
        drawdown_pct=Decimal(drawdown),
        max_drawdown_pct_since_ath=Decimal(max_drawdown),
        drawdown_bucket=QqqmEodStateCalculator._bucket(Decimal(drawdown)),
        phase=phase,
        base_target_weights=BaseTargetWeights(
            QQQM=Decimal(weights[0]),
            QLD=Decimal(weights[1]),
            TQQQ=Decimal(weights[2]),
        ),
        explanation=("직전 Python EOD 상태",),
    )


def calculate(close: str, previous: EodState | None = None) -> EodState:
    return QqqmEodStateCalculator().calculate(
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal(close),
            previous_state=previous if previous is not None else previous_state(),
        )
    )


def test_직전_python_상태가_없으면_초기화_정책을_요구한다() -> None:
    request = EodStateRequest(
        market_date=date(2026, 8, 18),
        qqqm_close=Decimal("100"),
    )

    with pytest.raises(BootstrapRequired, match="초기 상태 생성 정책"):
        QqqmEodStateCalculator().calculate(request)


@pytest.mark.parametrize("forbidden", ["vix", "ma200", "account", "quotes", "submit"])
def test_eod_입력에는_다음날_판단과_주문_값을_넣을_수_없다(forbidden: str) -> None:
    values = {
        "market_date": date(2026, 8, 18),
        "qqqm_close": Decimal("90"),
        "previous_state": previous_state(),
        forbidden: "허용하지 않는 값",
    }

    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        EodStateRequest.model_validate(values)


def test_신고가에서는_낙폭과_단계를_초기화한다() -> None:
    result = calculate("100.01")

    assert result.ath == Decimal("100.01")
    assert result.drawdown_pct == Decimal("0.0000")
    assert result.max_drawdown_pct_since_ath == Decimal("0.0000")
    assert result.phase is EodPhase.NORMAL
    assert result.base_target_weights == BaseTargetWeights(QQQM=100, QLD=0, TQQQ=0)


@pytest.mark.parametrize(
    ("close", "bucket", "weights"),
    [
        ("85", DrawdownBucket.FROM_15_TO_25, ("60", "30", "10")),
        ("75", DrawdownBucket.FROM_25_TO_35, ("40", "40", "20")),
        ("65", DrawdownBucket.FROM_35_TO_45, ("30", "30", "40")),
        ("55", DrawdownBucket.AT_LEAST_45, ("20", "20", "60")),
    ],
)
def test_낙폭_경계에서_승인된_기준비중을_선택한다(
    close: str,
    bucket: DrawdownBucket,
    weights: tuple[str, str, str],
) -> None:
    result = calculate(close)

    assert result.drawdown_bucket is bucket
    assert result.base_target_weights == BaseTargetWeights(
        QQQM=Decimal(weights[0]),
        QLD=Decimal(weights[1]),
        TQQQ=Decimal(weights[2]),
    )


@pytest.mark.parametrize(
    ("close", "bucket"),
    [
        ("85.0001", DrawdownBucket.LESS_THAN_15),
        ("75.0001", DrawdownBucket.FROM_15_TO_25),
        ("65.0001", DrawdownBucket.FROM_25_TO_35),
        ("55.0001", DrawdownBucket.FROM_35_TO_45),
    ],
)
def test_낙폭_경계_직전은_다음_구간으로_넘어가지_않는다(
    close: str,
    bucket: DrawdownBucket,
) -> None:
    assert calculate(close).drawdown_bucket is bucket


def test_최대낙폭_이후_10퍼센트까지_회복하면_recovery가_된다() -> None:
    result = calculate("90")

    assert result.drawdown_pct == Decimal("10.0000")
    assert result.phase is EodPhase.RECOVERY
    assert result.base_target_weights == BaseTargetWeights(QQQM=70, QLD=30, TQQQ=0)


def test_경계_판정은_표시용_반올림_전의_정확한_낙폭을_사용한다() -> None:
    before_activation = previous_state(
        close="86",
        drawdown="14",
        max_drawdown="14",
        phase=EodPhase.NORMAL,
        weights=("100", "0", "0"),
    )

    below_15 = calculate("85.00004", before_activation)
    above_10 = calculate("89.99996")

    assert below_15.drawdown_pct == Decimal("14.99996")
    assert below_15.phase is EodPhase.NORMAL
    assert above_10.drawdown_pct == Decimal("10.00004")
    assert above_10.phase is EodPhase.DRAWDOWN


def test_최대낙폭_이후_10초과_15미만은_직전_기준비중을_유지한다() -> None:
    result = calculate("89.9999")

    assert result.drawdown_pct == Decimal("10.0001")
    assert result.phase is EodPhase.DRAWDOWN
    assert result.base_target_weights == previous_state().base_target_weights


def test_15퍼센트_낙폭을_경험하지_않았다면_12퍼센트에서도_normal이다() -> None:
    before_activation = previous_state(
        close="86",
        drawdown="14",
        max_drawdown="14",
        phase=EodPhase.NORMAL,
        weights=("100", "0", "0"),
    )

    result = calculate("88", before_activation)

    assert result.phase is EodPhase.NORMAL
    assert result.base_target_weights == BaseTargetWeights(QQQM=100, QLD=0, TQQQ=0)


def test_이전_ath와_같은_종가는_새_신고가가_아니므로_recovery를_유지한다() -> None:
    result = calculate("100")

    assert result.ath == Decimal("100")
    assert result.max_drawdown_pct_since_ath == Decimal("20")
    assert result.phase is EodPhase.RECOVERY
    assert result.base_target_weights == BaseTargetWeights(QQQM=70, QLD=30, TQQQ=0)


def test_센트단위_ath의_순환소수_낙폭도_결정정밀도로_계산한다() -> None:
    new_high = calculate("100.01")

    result = QqqmEodStateCalculator().calculate(
        EodStateRequest(
            market_date=date(2026, 8, 19),
            qqqm_close=Decimal("90"),
            previous_state=new_high,
        )
    )

    assert result.drawdown_pct == Decimal("10.00899910008999100089991")
    assert result.phase is EodPhase.NORMAL


def test_같은_입력은_같은_결과와_checksum을_만든다() -> None:
    first = calculate("72.34")
    second = calculate("72.34")

    assert first == second
    assert first.checksum() == second.checksum()
    assert len(first.checksum()) == 64


def test_숫자표현만_다른_동일값은_같은_checksum을_만든다() -> None:
    previous = previous_state()
    calculator = QqqmEodStateCalculator()
    plain = calculator.calculate(
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal("72.34"),
            previous_state=previous,
        )
    )
    padded = calculator.calculate(
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal("72.3400"),
            previous_state=previous,
        )
    )

    assert plain == padded
    assert plain.checksum() == padded.checksum()


def test_설명_문구는_상태_checksum을_바꾸지_않는다() -> None:
    result = calculate("72.34")
    changed_explanation = result.model_copy(update={"explanation": ("다른 표시 문구",)})

    assert result.checksum() == changed_explanation.checksum()


def test_checksum은_계약의_known_vector와_일치한다() -> None:
    result = calculate("72.34")

    assert result.checksum() == "bcb41b9b885a21fa64c1ec98a1045e1b31d1584a87387c7d6d4c7a10bb3e3e1c"


@pytest.mark.parametrize("close", ["99", "85", "75", "65", "55"])
def test_모든_기준비중은_음수가_아니고_합이_100이다(close: str) -> None:
    weights = calculate(close).base_target_weights

    assert min(weights.QQQM, weights.QLD, weights.TQQQ) >= 0
    assert Decimal("100") == weights.QQQM + weights.QLD + weights.TQQQ


def test_시장일이_직전_상태보다_뒤가_아니면_거부한다() -> None:
    with pytest.raises(ValidationError, match="직전 EOD 시장일보다 뒤"):
        EodStateRequest(
            market_date=date(2026, 8, 17),
            qqqm_close=Decimal("90"),
            previous_state=previous_state(),
        )


def test_0이하_종가는_거부한다() -> None:
    with pytest.raises(ValidationError):
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal("0"),
            previous_state=previous_state(),
        )


@pytest.mark.parametrize("value", ["Infinity", "NaN"])
def test_유한하지_않은_종가는_거부한다(value: str) -> None:
    with pytest.raises(ValidationError):
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal(value),
            previous_state=previous_state(),
        )


@pytest.mark.parametrize("value", ["1E+1000", "1E-1000", "1" * 65])
def test_과도하게_큰_숫자표현은_평문으로_펼치기_전에_거부한다(value: str) -> None:
    with pytest.raises(ValidationError):
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal(value),
            previous_state=previous_state(),
        )


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("drawdown_pct", Decimal("19"), "현재 낙폭"),
        ("drawdown_bucket", DrawdownBucket.FROM_25_TO_35, "낙폭 구간"),
        ("phase", EodPhase.NORMAL, "저장된 단계"),
        ("base_target_weights", BaseTargetWeights(QQQM=33, QLD=33, TQQQ=34), "기준 비중"),
    ],
)
def test_서로_모순되는_직전상태를_거부한다(
    field: str,
    value: Decimal | DrawdownBucket | EodPhase | BaseTargetWeights,
    message: str,
) -> None:
    valid = previous_state().model_dump()
    valid[field] = value

    with pytest.raises(ValidationError, match=message):
        EodState.model_validate(valid)


def test_model_copy로_변조한_직전상태도_request_경계에서_다시_검증한다() -> None:
    corrupted = previous_state().model_copy(
        update={"max_drawdown_pct_since_ath": Decimal("0")}
    )

    with pytest.raises(ValidationError, match="최대 낙폭"):
        EodStateRequest(
            market_date=date(2026, 8, 18),
            qqqm_close=Decimal("90"),
            previous_state=corrupted,
        )


def test_경험하지_않은_깊은_낙폭의_비중은_회복구간에서_승계할_수_없다() -> None:
    with pytest.raises(ValidationError, match="기준 비중"):
        EodState(
            market_date=date(2026, 8, 17),
            ath=Decimal("100"),
            qqqm_close=Decimal("88"),
            drawdown_pct=Decimal("12"),
            max_drawdown_pct_since_ath=Decimal("15"),
            drawdown_bucket=DrawdownBucket.LESS_THAN_15,
            phase=EodPhase.DRAWDOWN,
            base_target_weights=BaseTargetWeights(QQQM=20, QLD=20, TQQQ=60),
            explanation=("도달할 수 없는 상태",),
        )


def test_실제로_45퍼센트_낙폭을_경험했다면_깊은_비중을_승계할_수_있다() -> None:
    state = EodState(
        market_date=date(2026, 8, 17),
        ath=Decimal("100"),
        qqqm_close=Decimal("88"),
        drawdown_pct=Decimal("12"),
        max_drawdown_pct_since_ath=Decimal("45"),
        drawdown_bucket=DrawdownBucket.LESS_THAN_15,
        phase=EodPhase.DRAWDOWN,
        base_target_weights=BaseTargetWeights(QQQM=20, QLD=20, TQQQ=60),
        explanation=("승계 가능한 상태",),
    )

    assert Decimal("60") == state.base_target_weights.TQQQ
