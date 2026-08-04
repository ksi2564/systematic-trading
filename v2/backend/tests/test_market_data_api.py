from pathlib import Path

from fastapi.testclient import TestClient

from wallant.config import Settings
from wallant.main import create_app


def app_settings(tmp_path: Path) -> Settings:
    return Settings(
        environment="test",
        database_url=f"sqlite+pysqlite:///{tmp_path / 'market-api.db'}",
        parquet_root=tmp_path / "market",
        credential_master_key=None,
    )


def daily_bar() -> dict:
    return {
        "trading_date": "2026-01-02",
        "symbol": "QQQM",
        "open": "100",
        "high": "102",
        "low": "99",
        "close": "101",
        "volume": "1234",
    }


def test_비공식_시세는_경고와_출처를_남기고_동일파일을_재사용한다(tmp_path) -> None:
    with TestClient(create_app(app_settings(tmp_path))) as client:
        without_warning = client.post(
            "/api/v2/market-data/bars",
            json={
                "market": "US",
                "resolution": "1d",
                "provider": "research/source",
                "official": False,
                "bars": [daily_bar()],
            },
        )
        assert without_warning.status_code == 422

        payload = {
            "market": "US",
            "resolution": "1d",
            "provider": "research/source",
            "official": False,
            "warning": "무료 연구 데이터로 실전 판단에 사용할 수 없음",
            "bars": [daily_bar()],
        }
        first = client.post("/api/v2/market-data/bars", json=payload)
        repeated = client.post("/api/v2/market-data/bars", json=payload)
        assert first.status_code == 201, first.text
        assert repeated.status_code == 201, repeated.text
        assert repeated.json()["id"] == first.json()["id"]
        assert Path(first.json()["file_path"]).is_relative_to((tmp_path / "market").resolve())

        catalog = client.get("/api/v2/market-data/catalog")
        assert catalog.status_code == 200
        assert len(catalog.json()) == 1
        assert catalog.json()[0]["provider"] == "research/source"
        assert catalog.json()[0]["official"] is False


def test_분봉은_실제_관측시각을_필수로_요구한다(tmp_path) -> None:
    with TestClient(create_app(app_settings(tmp_path))) as client:
        response = client.post(
            "/api/v2/market-data/bars",
            json={
                "market": "US",
                "resolution": "1m",
                "provider": "KIS",
                "official": True,
                "bars": [daily_bar()],
            },
        )
        assert response.status_code == 422
        assert "observed_at" in response.text
