import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from wallant.api.dependencies import get_actor
from wallant.config import Settings
from wallant.main import create_app
from wallant.persistence.models import Base
from wallant.security.credentials import CredentialCipher


def account_payload() -> dict:
    return {
        "name": "보안 테스트 계좌",
        "market": "US",
        "currency": "USD",
        "risk_policy": {
            "max_order_notional": "10000",
            "max_daily_notional": "30000",
            "max_daily_order_count": 10,
            "max_symbol_weight_pct": "100",
            "max_daily_loss": "1000",
        },
    }


def test_자격증명은_응답과_감사로그에_평문을_남기지_않는다(tmp_path: Path) -> None:
    settings = Settings(
        environment="test",
        database_url=f"sqlite+pysqlite:///{tmp_path / 'credentials.db'}",
        parquet_root=tmp_path / "market",
        credential_master_key=CredentialCipher.generate_key(),
    )
    with TestClient(create_app(settings)) as client:
        account = client.post("/api/v2/accounts", json=account_payload())
        assert account.status_code == 201
        account_id = account.json()["id"]
        stored = client.put(
            f"/api/v2/accounts/{account_id}/credentials",
            json={
                "app_key": "plain-app-key",
                "app_secret": "plain-app-secret",
                "account_number": "12345678",
                "product_code": "01",
            },
        )
        assert stored.status_code == 200, stored.text
        assert stored.json()["masked_account"] == "***5678"
        audit = client.get("/api/v2/operations/audit").json()
        serialized = json.dumps(audit, ensure_ascii=False)
        assert "ACCOUNT_CREDENTIALS_UPDATED" in serialized
        assert "plain-app-key" not in serialized
        assert "plain-app-secret" not in serialized
        assert all(event["created_at"].endswith("Z") for event in audit)
        assert client.get("/api/v2/operations/audit?limit=0").status_code == 422
        assert client.get("/api/v2/operations/audit?limit=501").status_code == 422


def test_같은_이름의_계좌는_명확한_충돌응답으로_거부한다(tmp_path: Path) -> None:
    settings = Settings(
        environment="test",
        database_url=f"sqlite+pysqlite:///{tmp_path / 'duplicate-account.db'}",
        parquet_root=tmp_path / "market",
    )
    with TestClient(create_app(settings)) as client:
        assert client.post("/api/v2/accounts", json=account_payload()).status_code == 201
        duplicate = client.post("/api/v2/accounts", json=account_payload())

    assert duplicate.status_code == 409
    assert duplicate.json()["detail"] == "같은 이름의 계좌가 이미 존재합니다."


def test_운영환경은_이메일_허용목록을_필수로_요구한다() -> None:
    settings = Settings(
        environment="production",
        database_url="mysql+pymysql://wallant:password@127.0.0.1/wallant",
    )
    with pytest.raises(RuntimeError, match="이메일 허용 목록"):
        settings.validate_execution_safety()


def test_운영요청은_cloudflare_access_사용자를_허용목록으로_검증한다() -> None:
    settings = Settings(
        environment="production",
        database_url="mysql+pymysql://wallant:password@127.0.0.1/wallant",
        allowed_access_emails=("owner@wall-ant.com",),
    )
    request = SimpleNamespace(
        app=SimpleNamespace(state=SimpleNamespace(settings=settings)),
    )
    assert get_actor(request, " OWNER@wall-ant.com ", None) == "owner@wall-ant.com"
    with pytest.raises(HTTPException) as missing:
        get_actor(request, None, None)
    assert missing.value.status_code == 401
    with pytest.raises(HTTPException) as denied:
        get_actor(request, "other@example.com", None)
    assert denied.value.status_code == 403


def test_운영_api의_조회경로도_access_인증을_요구한다(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(Settings, "validate_execution_safety", lambda self: None)
    settings = Settings(
        environment="production",
        database_url=f"sqlite+pysqlite:///{tmp_path / 'production-auth.db'}",
        parquet_root=tmp_path / "market",
        allowed_access_emails=("owner@wall-ant.com",),
    )
    app = create_app(settings)
    Base.metadata.create_all(app.state.engine)
    with TestClient(app) as client:
        assert client.get("/api/v2/operations/status").status_code == 401
        assert (
            client.get(
                "/api/v2/operations/status",
                headers={"Cf-Access-Authenticated-User-Email": "other@example.com"},
            ).status_code
            == 403
        )
        allowed = client.get(
            "/api/v2/operations/status",
            headers={"Cf-Access-Authenticated-User-Email": "owner@wall-ant.com"},
        )
        assert allowed.status_code == 200
