from datetime import date
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import event, func, select

from wallant.config import Settings
from wallant.main import create_app
from wallant.persistence.models import (
    AuditEventRecord,
    Base,
    GlobalControlRecord,
    MarketDataCatalogRecord,
)


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


def test_운영상태_GET은_전역제어행이_없어도_DB를_변경하지_않는다(tmp_path) -> None:
    app = create_app(app_settings(tmp_path))
    with TestClient(app) as client:
        with app.state.session_factory() as session:
            assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 0
            assert session.scalar(select(func.count()).select_from(AuditEventRecord)) == 0

        first = client.get("/api/v2/operations/status")
        second = client.get("/api/v2/operations/status")

        assert first.status_code == 200
        assert second.status_code == 200
        assert first.json()["global_emergency_paused"] is True
        assert first.json()["global_reason"] == "전역 안전 제어 상태를 확인할 수 없습니다."
        with app.state.session_factory() as session:
            assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 0
            assert session.scalar(select(func.count()).select_from(AuditEventRecord)) == 0


def database_snapshot(session) -> dict[str, list[dict]]:
    return {
        table.name: sorted(
            (dict(row) for row in session.execute(select(table)).mappings()),
            key=lambda row: repr(sorted(row.items())),
        )
        for table in Base.metadata.sorted_tables
    }


def test_배포_QA_허용_GET과_candidate_snapshot은_시드_DB를_변경하지_않는다(tmp_path) -> None:
    app = create_app(app_settings(tmp_path))
    with TestClient(app) as client:
        paused = client.post("/api/v2/operations/pause", json={"reason": "QA 시드 정지"})
        assert paused.status_code == 200
        with app.state.session_factory() as session:
            session.add(
                MarketDataCatalogRecord(
                    symbol="QQQM",
                    market="US",
                    resolution="1d",
                    provider="qa-provider",
                    official=True,
                    file_path="/private/qa/never-expose.parquet",
                    start_date=date(2026, 1, 1),
                    end_date=date(2026, 1, 2),
                    row_count=2,
                    checksum="sensitive-checksum",
                    warning="sensitive-warning",
                )
            )
            session.commit()
            before = database_snapshot(session)

        statements: list[str] = []

        def capture_statement(_connection, _cursor, statement, _parameters, _context, _many) -> None:
            statements.append(statement.lstrip().split(None, 1)[0].upper())

        event.listen(app.state.engine, "before_cursor_execute", capture_statement)
        try:
            responses = [
                client.get("/api/v2/operations/status"),
                client.get("/api/v2/strategies"),
                client.get("/api/v2/accounts"),
                client.get("/api/v2/market-data/catalog"),
                client.get("/api/v2/operations/audit?limit=30"),
                client.get("/api/v2/qa/deployed-read-only-snapshot"),
            ]
        finally:
            event.remove(app.state.engine, "before_cursor_execute", capture_statement)

        assert [response.status_code for response in responses] == [200] * 6
        snapshot = responses[-1].json()
        assert snapshot["schema_version"] == "1.0"
        assert snapshot["build_sha"] == "1111111111111111111111111111111111111111"
        assert sorted(snapshot["responses"]) == [
            "accounts",
            "market-data-catalog",
            "operations-audit-limit-30",
            "operations-status",
            "strategies",
        ]
        assert snapshot["responses"]["market-data-catalog"] == [
            {
                "id": "qa-catalog-1",
                "symbol": "QQQM",
                "market": "US",
                "resolution": "1d",
                "provider": "qa-provider",
                "official": True,
                "start_date": "2026-01-01",
                "end_date": "2026-01-02",
                "row_count": 2,
            }
        ]
        assert snapshot["responses"]["operations-audit-limit-30"] == [
            {
                "id": "qa-audit-1",
                "actor": "redacted",
                "action": "GLOBAL_PAUSED",
                "resource_type": "GLOBAL_CONTROL",
                "resource_id": "redacted",
                "details": {},
                "created_at": snapshot["responses"]["operations-audit-limit-30"][0]["created_at"],
            }
        ]
        serialized_snapshot = str(snapshot)
        assert "/private/qa/never-expose.parquet" not in serialized_snapshot
        assert "sensitive-checksum" not in serialized_snapshot
        assert "sensitive-warning" not in serialized_snapshot
        assert "QA 시드 정지" not in str(
            snapshot["responses"]["operations-audit-limit-30"]
        )
        with app.state.session_factory() as session:
            after = database_snapshot(session)
        assert after == before
        assert not ({"INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "ALTER", "DROP"} & set(statements))


def test_전역제어행과_감사는_명시적인_쓰기에서만_생성된다(tmp_path) -> None:
    app = create_app(app_settings(tmp_path))
    with TestClient(app) as client:
        paused = client.post("/api/v2/operations/pause", json={"reason": "QA 보호 정지"})
        assert paused.status_code == 200
        assert paused.json() == {"emergency_paused": True, "reason": "QA 보호 정지"}

        status = client.get("/api/v2/operations/status")
        assert status.json()["global_emergency_paused"] is True
        with app.state.session_factory() as session:
            assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 1
            assert session.scalar(select(func.count()).select_from(AuditEventRecord)) == 1


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
