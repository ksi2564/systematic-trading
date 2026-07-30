from __future__ import annotations

from dataclasses import asdict, is_dataclass
from datetime import UTC, date, datetime
from decimal import Decimal
from enum import Enum
from pathlib import Path
from typing import Any
from uuid import UUID

from pydantic import BaseModel

from wallant.persistence.models import AccountRecord, StrategyRecord


def json_value(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if is_dataclass(value):
        return json_value(asdict(value))
    if isinstance(value, dict):
        return {str(key): json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [json_value(item) for item in value]
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (UUID, Path)):
        return str(value)
    if isinstance(value, datetime):
        return utc_iso(value)
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Enum):
        return value.value
    return value


def utc_iso(value: datetime) -> str:
    normalized = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return normalized.astimezone(UTC).isoformat().replace("+00:00", "Z")


def strategy_record(record: StrategyRecord) -> dict[str, Any]:
    return {
        "id": record.id,
        "name": record.name,
        "description": record.description,
        "created_at": utc_iso(record.created_at),
        "updated_at": utc_iso(record.updated_at),
        "versions": [
            {
                "id": version.id,
                "version": version.version,
                "lifecycle": version.lifecycle,
                "checksum": version.checksum,
                "definition": version.definition,
                "created_at": utc_iso(version.created_at),
                "approved_at": utc_iso(version.approved_at) if version.approved_at else None,
            }
            for version in record.versions
        ],
    }


def account_record(record: AccountRecord) -> dict[str, Any]:
    profile = record.execution_profile
    risk = record.risk_policy
    return {
        "id": record.id,
        "name": record.name,
        "broker": record.broker,
        "market": record.market,
        "currency": record.currency,
        "status": record.status,
        "status_reason": record.status_reason,
        "active_strategy_version_id": record.active_strategy_version_id,
        "execution_profile": (
            {
                "order_type": profile.order_type,
                "schedule": profile.schedule,
                "slices": profile.slices,
                "max_reprice_attempts": profile.max_reprice_attempts,
                "reprice_ticks": profile.reprice_ticks,
                "fee_rate_pct": str(profile.fee_rate_pct),
                "fx_mode": profile.fx_mode,
                "max_auto_fx_amount": (
                    str(profile.max_auto_fx_amount) if profile.max_auto_fx_amount else None
                ),
            }
            if profile
            else None
        ),
        "risk_policy": (
            {
                "max_order_notional": str(risk.max_order_notional),
                "max_daily_notional": str(risk.max_daily_notional),
                "max_daily_order_count": risk.max_daily_order_count,
                "max_symbol_weight_pct": str(risk.max_symbol_weight_pct),
                "max_daily_loss": str(risk.max_daily_loss),
                "max_reprice_attempts": risk.max_reprice_attempts,
            }
            if risk
            else None
        ),
        "created_at": utc_iso(record.created_at),
        "updated_at": utc_iso(record.updated_at),
    }
