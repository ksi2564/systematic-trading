from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from hashlib import sha256
from pathlib import Path
from uuid import uuid4

import pyarrow as pa
import pyarrow.parquet as pq

from wallant.research.models import MarketBar


@dataclass(frozen=True, slots=True)
class CatalogEntry:
    symbol: str
    market: str
    resolution: str
    provider: str
    official: bool
    file_path: Path
    start_date: date
    end_date: date
    row_count: int
    checksum: str
    warning: str | None


class ParquetMarketDataStore:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()

    def write_bars(
        self,
        *,
        market: str,
        resolution: str,
        provider: str,
        official: bool,
        bars: list[MarketBar],
        warning: str | None = None,
    ) -> CatalogEntry:
        if not bars:
            raise ValueError("저장할 시장 데이터가 없습니다.")
        normalized_market = market.strip().upper()
        if normalized_market not in {"US", "KRX"}:
            raise ValueError("지원하지 않는 시장입니다.")
        if resolution not in {"1d", "1m"}:
            raise ValueError("지원하지 않는 데이터 해상도입니다.")
        if resolution == "1m" and any(bar.observed_at is None for bar in bars):
            raise ValueError("분봉 데이터에는 observed_at 시각이 필요합니다.")
        symbols = {bar.symbol for bar in bars}
        if len(symbols) != 1:
            raise ValueError("Parquet 파일 하나에는 한 종목만 저장합니다.")
        symbol = next(iter(symbols))
        ordered = sorted(
            bars,
            key=lambda item: (
                item.trading_date,
                item.observed_at.isoformat() if item.observed_at else "",
            ),
        )
        year = ordered[0].trading_date.year
        if any(bar.trading_date.year != year for bar in ordered):
            raise ValueError("Parquet 파일 하나에는 한 연도의 데이터만 저장합니다.")
        identities = [
            (
                bar.trading_date,
                bar.observed_at.isoformat() if resolution == "1m" and bar.observed_at else None,
            )
            for bar in ordered
        ]
        if len(set(identities)) != len(identities):
            raise ValueError("같은 시점의 중복 시장 데이터가 있습니다.")

        directory = self.root / normalized_market / resolution / symbol
        directory.mkdir(parents=True, exist_ok=True)
        table = pa.table(
            {
                "trading_date": [bar.trading_date for bar in ordered],
                "observed_at": [bar.observed_at for bar in ordered],
                "symbol": [bar.symbol for bar in ordered],
                "open": [bar.open for bar in ordered],
                "high": [bar.high for bar in ordered],
                "low": [bar.low for bar in ordered],
                "close": [bar.close for bar in ordered],
                "volume": [bar.volume for bar in ordered],
                "available_at": [bar.available_at for bar in ordered],
                "provider": [provider for _bar in ordered],
                "official": [official for _bar in ordered],
            }
        )
        temporary_path = directory / f".{year}-{uuid4().hex}.tmp.parquet"
        pq.write_table(table, temporary_path, compression="zstd")
        checksum = sha256(temporary_path.read_bytes()).hexdigest()
        provider_slug = re.sub(r"[^a-z0-9._-]+", "-", provider.lower()).strip(".-")
        provider_slug = (provider_slug or "provider")[:40]
        path = directory / f"{year}-{provider_slug}-{checksum[:12]}.parquet"
        if path.exists():
            temporary_path.unlink()
        else:
            temporary_path.replace(path)
        return CatalogEntry(
            symbol=symbol,
            market=normalized_market,
            resolution=resolution,
            provider=provider,
            official=official,
            file_path=path,
            start_date=ordered[0].trading_date,
            end_date=ordered[-1].trading_date,
            row_count=len(ordered),
            checksum=checksum,
            warning=warning,
        )

    def read_bars(self, path: Path) -> list[MarketBar]:
        resolved = path.resolve()
        if not resolved.is_relative_to(self.root):
            raise ValueError("시장 데이터 루트 밖의 파일은 읽을 수 없습니다.")
        table = pq.read_table(resolved)
        return [
            MarketBar(
                trading_date=row["trading_date"],
                observed_at=row["observed_at"],
                symbol=row["symbol"],
                open=row["open"],
                high=row["high"],
                low=row["low"],
                close=row["close"],
                volume=row["volume"],
                available_at=row["available_at"],
                provider=row["provider"],
                official=row["official"],
            )
            for row in table.to_pylist()
        ]
