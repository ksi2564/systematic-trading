from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from wallant.api.dependencies import get_actor, get_session
from wallant.api.schemas import StoreBarsRequest
from wallant.persistence.models import AuditEventRecord, MarketDataCatalogRecord
from wallant.persistence.parquet_store import ParquetMarketDataStore

router = APIRouter(prefix="/market-data", tags=["market-data"])


@router.get("/catalog")
def catalog(session: Session = Depends(get_session)) -> list[dict]:
    rows = session.scalars(
        select(MarketDataCatalogRecord).order_by(
            MarketDataCatalogRecord.symbol,
            MarketDataCatalogRecord.start_date,
        )
    )
    return [
        {
            "id": row.id,
            "symbol": row.symbol,
            "market": row.market,
            "resolution": row.resolution,
            "provider": row.provider,
            "official": row.official,
            "file_path": row.file_path,
            "start_date": row.start_date.isoformat(),
            "end_date": row.end_date.isoformat(),
            "row_count": row.row_count,
            "checksum": row.checksum,
            "warning": row.warning,
        }
        for row in rows
    ]


@router.post("/bars", status_code=status.HTTP_201_CREATED)
def store_bars(
    payload: StoreBarsRequest,
    request: Request,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    symbols = {bar.symbol for bar in payload.bars}
    years = {bar.trading_date.year for bar in payload.bars}
    if len(symbols) != 1 or len(years) != 1:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="요청 하나에는 한 종목·한 연도의 데이터만 저장할 수 있습니다.",
        )
    if not payload.official and not payload.warning:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="비공식 데이터에는 품질 경고가 필요합니다.",
        )
    store = ParquetMarketDataStore(request.app.state.settings.parquet_root)
    try:
        entry = store.write_bars(
            market=payload.market,
            resolution=payload.resolution,
            provider=payload.provider,
            official=payload.official,
            bars=payload.bars,
            warning=payload.warning,
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    record = MarketDataCatalogRecord(
        symbol=entry.symbol,
        market=entry.market,
        resolution=entry.resolution,
        provider=entry.provider,
        official=entry.official,
        file_path=str(entry.file_path),
        start_date=entry.start_date,
        end_date=entry.end_date,
        row_count=entry.row_count,
        checksum=entry.checksum,
        warning=entry.warning,
    )
    existing = session.scalar(
        select(MarketDataCatalogRecord).where(
            MarketDataCatalogRecord.symbol == entry.symbol,
            MarketDataCatalogRecord.resolution == entry.resolution,
            MarketDataCatalogRecord.provider == entry.provider,
            MarketDataCatalogRecord.file_path == str(entry.file_path),
        )
    )
    if existing:
        return {
            "id": existing.id,
            "symbol": existing.symbol,
            "file_path": existing.file_path,
            "row_count": existing.row_count,
            "checksum": existing.checksum,
        }
    session.add(record)
    session.flush()
    session.add(
        AuditEventRecord(
            actor=actor,
            action="MARKET_DATA_STORED",
            resource_type="MARKET_DATA",
            resource_id=record.id,
            details={
                "symbol": record.symbol,
                "resolution": record.resolution,
                "provider": record.provider,
                "official": record.official,
                "checksum": record.checksum,
            },
        )
    )
    session.commit()
    return {
        "id": record.id,
        "symbol": record.symbol,
        "file_path": record.file_path,
        "row_count": record.row_count,
        "checksum": record.checksum,
    }
