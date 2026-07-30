from datetime import date
from decimal import Decimal

import pytest
from cryptography.exceptions import InvalidTag

from wallant.persistence.parquet_store import ParquetMarketDataStore
from wallant.research.models import MarketBar
from wallant.security.credentials import CredentialCipher


def test_자격증명은_aes_gcm으로_왕복하고_계좌에_바인딩한다() -> None:
    cipher = CredentialCipher(CredentialCipher.generate_key())
    encrypted = cipher.encrypt("account-a", {"app_key": "key", "app_secret": "secret"})
    assert b"secret" not in encrypted.ciphertext
    assert cipher.decrypt("account-a", encrypted)["app_secret"] == "secret"
    with pytest.raises(InvalidTag):
        cipher.decrypt("account-b", encrypted)


def test_parquet_시계열을_연도와_종목_단위로_저장한다(tmp_path) -> None:
    store = ParquetMarketDataStore(tmp_path)
    values = [
        MarketBar(
            trading_date=date(2025, 1, day),
            symbol="QQQM",
            open=Decimal("100"),
            high=Decimal("105"),
            low=Decimal("99"),
            close=Decimal(str(100 + day)),
            volume=1000,
            provider="test",
        )
        for day in (2, 3)
    ]
    entry = store.write_bars(
        market="US",
        resolution="1d",
        provider="fixture",
        official=False,
        bars=values,
        warning="테스트 데이터",
    )
    loaded = store.read_bars(entry.file_path)
    assert entry.row_count == 2
    assert loaded[0].symbol == "QQQM"
    assert loaded[1].close == Decimal("103")

    repeated = store.write_bars(
        market="US",
        resolution="1d",
        provider="../../fixture",
        official=False,
        bars=values,
        warning="테스트 데이터",
    )
    assert repeated.file_path.is_relative_to(tmp_path.resolve())
    assert ".." not in repeated.file_path.name


def test_경로문자가_포함된_종목코드는_거부한다() -> None:
    with pytest.raises(ValueError, match="종목 코드"):
        MarketBar(
            trading_date=date(2025, 1, 2),
            symbol="../../secret",
            open=100,
            high=101,
            low=99,
            close=100,
        )


def test_parquet_저장경로의_시장과_중복시점을_검증한다(tmp_path) -> None:
    bar = MarketBar(
        trading_date=date(2025, 1, 2),
        symbol="QQQM",
        open=100,
        high=101,
        low=99,
        close=100,
    )
    store = ParquetMarketDataStore(tmp_path)
    with pytest.raises(ValueError, match="시장"):
        store.write_bars(
            market="../../outside",
            resolution="1d",
            provider="fixture",
            official=False,
            bars=[bar],
        )
    with pytest.raises(ValueError, match="중복"):
        store.write_bars(
            market="US",
            resolution="1d",
            provider="fixture",
            official=False,
            bars=[bar, bar],
        )


def test_ohlcv_범위가_모순되면_거부한다() -> None:
    with pytest.raises(ValueError, match="범위"):
        MarketBar(
            trading_date=date(2025, 1, 2),
            symbol="QQQM",
            open=102,
            high=101,
            low=99,
            close=100,
        )
    with pytest.raises(ValueError, match="거래량"):
        MarketBar(
            trading_date=date(2025, 1, 2),
            symbol="QQQM",
            open=100,
            high=101,
            low=99,
            close=100,
            volume=-1,
        )
