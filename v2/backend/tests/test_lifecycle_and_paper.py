from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path
from threading import Event, Lock

from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from wallant.api import research as research_api
from wallant.config import Settings
from wallant.main import create_app
from wallant.persistence.models import DailyRiskUsageRecord, StrategyVersionRecord


def app_settings(tmp_path: Path) -> Settings:
    return Settings(
        environment="test",
        database_url=f"sqlite+pysqlite:///{tmp_path / 'lifecycle.db'}",
        parquet_root=tmp_path / "market",
        credential_master_key=None,
    )


def price_bars(days: int = 6) -> list[dict]:
    rows: list[dict] = []
    start = date(2026, 1, 1)
    for offset in range(days):
        trading_date = start + timedelta(days=offset)
        for symbol, price in (("QQQM", 100 - offset), ("QLD", 70 - offset), ("TQQQ", 50 - offset)):
            rows.append(
                {
                    "trading_date": trading_date.isoformat(),
                    "symbol": symbol,
                    "open": str(price),
                    "high": str(price + 1),
                    "low": str(price - 1),
                    "close": str(price),
                    "volume": "1000",
                }
            )
    return rows


def paper_payload(as_of: str = "2026-01-10", qqqm_close: str = "90") -> dict:
    return {
        "context": {
            "as_of": as_of,
            "market": {"QQQM.close": qqqm_close},
            "history": {"QQQM": ["100"]},
        },
        "portfolio": {
            "cash": "100000",
            "positions": [],
            "currency": "USD",
        },
        "quotes": {"QQQM": "90", "QLD": "65", "TQQQ": "45"},
    }


def account_payload(name: str, market: str, currency: str) -> dict:
    return {
        "name": name,
        "market": market,
        "currency": currency,
        "risk_policy": {
            "max_order_notional": "10000",
            "max_daily_notional": "30000",
            "max_daily_order_count": 10,
            "max_symbol_weight_pct": "100",
            "max_daily_loss": "1000",
        },
    }


def test_전략승격은_각단계의_완료증거를_요구하고_모의입력은_멱등하다(tmp_path) -> None:
    app = create_app(app_settings(tmp_path))
    with TestClient(app) as client:
        created = client.post("/api/v2/strategies/baseline/qqqm")
        assert created.status_code == 201
        version_id = created.json()["id"]

        no_backtest = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "BACKTESTED"},
        )
        assert no_backtest.status_code == 409
        assert "BACKTEST" in no_backtest.json()["detail"]

        backtest = client.post(
            "/api/v2/research/backtests",
            json={"version_id": version_id, "bars": price_bars()},
        )
        assert backtest.status_code == 200, backtest.text
        transitioned = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "BACKTESTED", "evidence": {"reviewed": True}},
        )
        assert transitioned.status_code == 200

        no_rolling = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "ROLLING_VALIDATED"},
        )
        assert no_rolling.status_code == 409
        rolling = client.post(
            "/api/v2/research/rolling",
            json={
                "version_id": version_id,
                "bars": price_bars(),
                "window_days": 3,
                "step_days": 1,
            },
        )
        assert rolling.status_code == 200, rolling.text
        assert rolling.json()["fixed_parameters"] is True
        transitioned = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "ROLLING_VALIDATED"},
        )
        assert transitioned.status_code == 200
        paper_transition = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "PAPER"},
        )
        assert paper_transition.status_code == 200

        us_account = client.post(
            "/api/v2/accounts",
            json=account_payload("미국 계좌", "US", "USD"),
        ).json()
        krx_account = client.post(
            "/api/v2/accounts",
            json=account_payload("한국 계좌", "KRX", "KRW"),
        ).json()
        mismatched_paper = client.post(
            "/api/v2/research/paper/sessions",
            json={"version_id": version_id, "account_id": krx_account["id"]},
        )
        assert mismatched_paper.status_code == 409
        assert "시장 전략" in mismatched_paper.json()["detail"]

        paper = client.post(
            "/api/v2/research/paper/sessions",
            json={"version_id": version_id, "account_id": us_account["id"]},
        )
        assert paper.status_code == 201, paper.text
        paper_id = paper.json()["id"]
        premature = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/complete",
            json={"confirmed": True},
        )
        assert premature.status_code == 409

        override_payload = paper_payload(as_of="2026-01-08")
        override_payload["risk_policy"] = {
            "max_order_notional": "1000000",
            "max_daily_notional": "1000000",
            "max_daily_order_count": 100,
            "max_symbol_weight_pct": "100",
            "max_daily_loss": "1000000",
        }
        override = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/steps",
            json=override_payload,
        )
        assert override.status_code == 422
        assert "risk_policy" in override.text

        failed_payload = paper_payload(as_of="2026-01-09")
        failed_payload["context"]["market"] = {}
        failed = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/steps",
            json=failed_payload,
        )
        repeated_failure = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/steps",
            json=failed_payload,
        )
        assert failed.status_code == repeated_failure.status_code == 422
        assert failed.json() == repeated_failure.json()

        with Session(app.state.engine) as database:
            database.add(
                DailyRiskUsageRecord(
                    account_id=us_account["id"],
                    usage_date=date(2026, 1, 10),
                    order_notional=0,
                    order_count=0,
                    realized_loss=1000,
                )
            )
            database.commit()

        first_step = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/steps",
            json=paper_payload(),
        )
        assert first_step.status_code == 200, first_step.text
        assert any(
            "MAX_DAILY_LOSS" in intent["violations"]
            for intent in first_step.json()["order_plan"]["intents"]
        )
        assert any(
            "MAX_ORDER_NOTIONAL" in intent["violations"]
            for intent in first_step.json()["order_plan"]["intents"]
        )
        intent_count = client.get("/api/v2/operations/status").json()["counts"]["order_intents"]
        repeated_step = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/steps",
            json=paper_payload(),
        )
        assert repeated_step.status_code == 200
        assert repeated_step.json() == first_step.json()
        assert client.get("/api/v2/operations/status").json()["counts"]["order_intents"] == intent_count

        conflicting_step = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/steps",
            json=paper_payload(qqqm_close="89"),
        )
        assert conflicting_step.status_code == 409

        completed = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/complete",
            json={"confirmed": True, "note": "수동 검토 완료"},
        )
        assert completed.status_code == 200
        assert completed.json()["summary"]["evaluated_days"] == 1
        assert completed.json()["summary"]["error_count"] == 1
        assert completed.json()["completed_at"].endswith("Z")
        repeated_completion = client.post(
            f"/api/v2/research/paper/sessions/{paper_id}/complete",
            json={"confirmed": True},
        )
        assert repeated_completion.status_code == 200
        assert repeated_completion.json()["completed_at"].endswith("Z")

        repeated_paper = client.post(
            "/api/v2/research/paper/sessions",
            json={"version_id": version_id, "account_id": us_account["id"]},
        )
        assert repeated_paper.status_code == 201
        repeated_paper_id = repeated_paper.json()["id"]
        repeated_paper_step = client.post(
            f"/api/v2/research/paper/sessions/{repeated_paper_id}/steps",
            json=paper_payload(),
        )
        assert repeated_paper_step.status_code == 200, repeated_paper_step.text
        repeated_paper_completion = client.post(
            f"/api/v2/research/paper/sessions/{repeated_paper_id}/complete",
            json={"confirmed": True},
        )
        assert repeated_paper_completion.status_code == 200

        no_confirmation = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "LIVE_APPROVED", "confirmed": False},
        )
        assert no_confirmation.status_code == 400
        approved = client.post(
            f"/api/v2/strategies/versions/{version_id}/transition",
            json={"target": "LIVE_APPROVED", "confirmed": True},
        )
        assert approved.status_code == 200
        assert approved.json()["lifecycle"] == "LIVE_APPROVED"

        assigned = client.post(
            f"/api/v2/accounts/{us_account['id']}/strategy",
            json={"strategy_version_id": version_id},
        )
        assert assigned.status_code == 200

        mismatched = client.post(
            f"/api/v2/accounts/{krx_account['id']}/strategy",
            json={"strategy_version_id": version_id},
        )
        assert mismatched.status_code == 409
        assert "시장 전략" in mismatched.json()["detail"]


def test_동일_모의입력의_동시요청은_한번만_저장하고_매도손실을_누적한다(
    tmp_path,
    monkeypatch,
) -> None:
    app = create_app(app_settings(tmp_path))
    with TestClient(app, raise_server_exceptions=False) as client:
        created = client.post(
            "/api/v2/strategies",
            json={
                "definition": {
                    "name": "동시 모의 청산",
                    "engine": "SIGNAL_TRADING_V1",
                    "market": "US",
                    "universe": {"market": "US", "symbols": ["AAPL"]},
                    "signal_symbol": "AAPL",
                    "signal_rules": {"kind": "PRICE_MA_CROSS", "ma_period": 2},
                }
            },
        ).json()
        with Session(app.state.engine) as database:
            version = database.get(StrategyVersionRecord, created["id"])
            assert version is not None
            version.lifecycle = "PAPER"
            database.commit()

        account = client.post(
            "/api/v2/accounts",
            json=account_payload("동시 요청 계좌", "US", "USD"),
        ).json()
        paper_id = client.post(
            "/api/v2/research/paper/sessions",
            json={"version_id": created["id"], "account_id": account["id"]},
        ).json()["id"]
        payload = {
            "context": {
                "as_of": "2026-02-01",
                "market": {"AAPL.close": "80"},
                "history": {"AAPL": ["100", "110"]},
            },
            "portfolio": {
                "cash": "0",
                "positions": [
                    {
                        "symbol": "AAPL",
                        "quantity": "10",
                        "average_price": "100",
                        "market_price": "80",
                    }
                ],
                "currency": "USD",
            },
            "quotes": {"AAPL": "80"},
        }

        original_step = research_api.PaperTradingService.step
        first_entered = Event()
        release_first = Event()
        call_guard = Lock()
        first_call = True

        def slow_first_step(self, *args, **kwargs):
            nonlocal first_call
            with call_guard:
                should_wait = first_call
                first_call = False
            if should_wait:
                first_entered.set()
                assert release_first.wait(timeout=5)
            return original_step(self, *args, **kwargs)

        monkeypatch.setattr(research_api.PaperTradingService, "step", slow_first_step)

        def post_step():
            return client.post(
                f"/api/v2/research/paper/sessions/{paper_id}/steps",
                json=payload,
            )

        second_started = Event()

        def post_second_step():
            second_started.set()
            return post_step()

        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(post_step)
            assert first_entered.wait(timeout=5)
            second = executor.submit(post_second_step)
            assert second_started.wait(timeout=5)
            release_first.set()
            responses = [first.result(timeout=5), second.result(timeout=5)]

        assert [response.status_code for response in responses] == [200, 200]
        assert responses[0].json() == responses[1].json()
        with Session(app.state.engine) as database:
            usage = database.scalar(
                select(DailyRiskUsageRecord).where(
                    DailyRiskUsageRecord.account_id == account["id"],
                    DailyRiskUsageRecord.usage_date == date(2026, 2, 1),
                )
            )
            assert usage is not None
            assert usage.realized_loss == 200
            assert usage.order_count == 1


def test_실행플래그는_설정객체를_직접_주입해도_거부한다(tmp_path) -> None:
    settings = app_settings(tmp_path).model_copy(update={"execution_enabled": True})
    try:
        create_app(settings)
    except RuntimeError as exc:
        assert "실제 주문 실행" in str(exc)
    else:
        raise AssertionError("실행 플래그가 활성화된 앱이 생성되었습니다.")


def test_실브로커_이름도_설정단계에서_거부한다(tmp_path) -> None:
    settings = app_settings(tmp_path).model_copy(update={"broker_adapter": "kis"})
    try:
        create_app(settings)
    except RuntimeError as exc:
        assert "비활성 브로커" in str(exc)
    else:
        raise AssertionError("실브로커 이름이 설정된 앱이 생성되었습니다.")
