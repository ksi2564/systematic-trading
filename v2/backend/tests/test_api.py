from datetime import date
from pathlib import Path

from fastapi.testclient import TestClient

from wallant.config import Settings
from wallant.main import create_app


def app_settings(tmp_path: Path) -> Settings:
    return Settings(
        environment="test",
        build_sha="1111111111111111111111111111111111111111",
        database_url=f"sqlite+pysqlite:///{tmp_path / 'test.db'}",
        parquet_root=tmp_path / "market",
        credential_master_key=None,
    )


def test_기준전략_생성_평가와_운영상태_조회(tmp_path) -> None:
    with TestClient(create_app(app_settings(tmp_path))) as client:
        created = client.post("/api/v2/strategies/baseline/qqqm")
        assert created.status_code == 201, created.text
        version_id = created.json()["id"]
        response = client.post(
            "/api/v2/research/evaluate",
            json={
                "version_id": version_id,
                "context": {
                    "as_of": date(2026, 1, 1).isoformat(),
                    "market": {"QQQM.close": "85"},
                    "history": {"QQQM": ["100"]},
                },
            },
        )
        assert response.status_code == 200, response.text
        assert response.json()["state"]["phase"] == "DRAWDOWN"
        status = client.get("/api/v2/operations/status")
        assert status.status_code == 200
        assert status.headers["cache-control"] == "no-store"
        assert status.json()["execution_enabled"] is False
        assert status.json()["build_sha"] == "1111111111111111111111111111111111111111"
        health = client.get("/health")
        assert health.status_code == 200
        assert health.headers["cache-control"] == "no-store"
        assert health.json()["build_sha"] == "1111111111111111111111111111111111111111"


def test_계좌는_위험한도_없이_만들_수_없고_신규상태는_정지다(tmp_path) -> None:
    with TestClient(create_app(app_settings(tmp_path))) as client:
        response = client.post(
            "/api/v2/accounts",
            json={
                "name": "미국 주식 계좌",
                "market": "US",
                "currency": "USD",
                "risk_policy": {
                    "max_buy_order_notional": "10000",
                    "max_daily_buy_notional": "20000",
                    "max_daily_order_count": 10,
                    "max_symbol_weight_pct": "100",
                    "max_daily_loss": "1000",
                },
            },
        )
        assert response.status_code == 201, response.text
        assert response.json()["status"] == "PAUSED"
        assert response.json()["risk_policy"]["max_daily_order_count"] == 10


def test_전략별_파라미터는_저장전에_타입과_범위를_검증한다(tmp_path) -> None:
    base_definition = {
        "name": "파라미터 검증 전략",
        "engine": "SIGNAL_TRADING_V1",
        "market": "US",
        "universe": {"market": "US", "symbols": ["AAPL"]},
        "signal_symbol": "AAPL",
        "signal_rules": {"kind": "PRICE_MA_CROSS", "ma_period": 2},
    }
    with TestClient(create_app(app_settings(tmp_path))) as client:
        negative_fee = client.post(
            "/api/v2/strategies",
            json={
                "definition": {
                    **base_definition,
                    "parameters": {"fee_rate_pct": "-0.1"},
                }
            },
        )
        unknown_parameter = client.post(
            "/api/v2/strategies",
            json={
                "definition": {
                    **base_definition,
                    "parameters": {"typo_fee_rate_pct": "0.1"},
                }
            },
        )

    assert negative_fee.status_code == 422
    assert "fee_rate_pct" in negative_fee.text
    assert unknown_parameter.status_code == 422
    assert "typo_fee_rate_pct" in unknown_parameter.text


def test_제거한_실행프로필과_screen_종목군은_api가_받지_않는다(tmp_path) -> None:
    with TestClient(create_app(app_settings(tmp_path))) as client:
        account = client.post(
            "/api/v2/accounts",
            json={
                "name": "구 실행 프로필",
                "market": "US",
                "currency": "USD",
                "execution_profile": {"order_type": "LIMIT"},
                "risk_policy": {
                    "max_buy_order_notional": "10000",
                    "max_daily_buy_notional": "20000",
                    "max_daily_order_count": 10,
                    "max_symbol_weight_pct": "100",
                    "max_daily_loss": "1000",
                },
            },
        )
        screen_strategy = client.post(
            "/api/v2/strategies",
            json={
                "definition": {
                    "name": "구 SCREEN 전략",
                    "engine": "SIGNAL_TRADING_V1",
                    "market": "US",
                    "signal_symbol": "SPY",
                    "universe": {
                        "kind": "SCREEN",
                        "market": "US",
                        "symbols": ["SPY"],
                        "filters": [{"field": "market_cap", "operator": "GT", "value": 1}],
                    },
                    "signal_rules": {"kind": "HIGH_BREAKOUT"},
                }
            },
        )

    assert account.status_code == 422
    assert "execution_profile" in account.text
    assert screen_strategy.status_code == 422
    assert "kind" in screen_strategy.text


def test_조건식_전략도_폼_스키마로_저장하고_평가한다(tmp_path) -> None:
    definition = {
        "name": "200일선 자산배분",
        "engine": "RULE_ALLOCATION_V1",
        "market": "US",
        "signal_symbol": "SPY",
        "universe": {
            "market": "US",
            "symbols": ["SPY", "SHY"],
        },
        "rules": [
            {
                "name": "방어",
                "priority": 1,
                "when": {
                    "conditions": [
                        {
                            "left": {"source": "market", "key": "SPY.close"},
                            "operator": "LT",
                            "right": {"source": "market", "key": "SPY.ma_200"},
                        }
                    ]
                },
                "target_weights": {"SHY": "100"},
                "next_state": "DEFENSIVE",
            },
            {
                "name": "기본",
                "priority": 99,
                "when": {
                    "conditions": [
                        {
                            "left": {"source": "constant", "value": "1"},
                            "operator": "EQ",
                            "right": {"source": "constant", "value": "1"},
                        }
                    ]
                },
                "target_weights": {"SPY": "100"},
            },
        ],
    }
    with TestClient(create_app(app_settings(tmp_path))) as client:
        created = client.post("/api/v2/strategies", json={"definition": definition})
        assert created.status_code == 201, created.text
        evaluated = client.post(
            "/api/v2/research/evaluate",
            json={
                "version_id": created.json()["id"],
                "context": {
                    "as_of": "2026-01-01",
                    "market": {"SPY.close": "90", "SPY.ma_200": "100"},
                },
            },
        )
        assert evaluated.status_code == 200, evaluated.text
        assert evaluated.json()["target_weights"] == {"SHY": "100", "SPY": "0"}
