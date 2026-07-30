from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

from sqlalchemy import Engine, inspect, select, text
from sqlalchemy.orm import Session

from wallant.persistence.models import AccountRecord, LegacyHistoryRecord


@dataclass(frozen=True, slots=True)
class LegacyTableSpec:
    table: str
    history_kind: str
    default_id_columns: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class LegacyMigrationPreview:
    destination_account_id: UUID
    available: dict[str, int]
    missing: tuple[str, ...]
    excluded_present: tuple[str, ...]
    total_rows: int


@dataclass(frozen=True, slots=True)
class LegacyMigrationResult:
    imported: dict[str, int]
    skipped_duplicates: int


SELECTED_TABLES = (
    LegacyTableSpec("strategy_state", "STRATEGY_STATE", ("id", "as_of_date")),
    LegacyTableSpec("portfolio_snapshot", "PORTFOLIO_SNAPSHOT", ("id", "snapshot_date")),
    LegacyTableSpec(
        "performance_analytics_snapshot",
        "PERFORMANCE_SNAPSHOT",
        ("id", "snapshot_date"),
    ),
    LegacyTableSpec("execution_job", "EXECUTION_JOB", ("id", "signal_date")),
    LegacyTableSpec("execution_order", "EXECUTION_ORDER", ("id",)),
)
EXCLUDED_TABLES = (
    "operation_mode_audit",
    "parameter_registry_record",
    "parameter_change_event",
    "trading_control",
)


class LegacyMigrationService:
    """원본 DB를 수정하지 않고 선택한 행을 읽기 전용 이력으로 복사한다."""

    def preview(
        self,
        source_engine: Engine,
        destination_account_id: UUID,
    ) -> LegacyMigrationPreview:
        inspector = inspect(source_engine)
        names = set(inspector.get_table_names())
        available: dict[str, int] = {}
        missing: list[str] = []
        with source_engine.connect() as connection:
            for spec in SELECTED_TABLES:
                if spec.table not in names:
                    missing.append(spec.table)
                    continue
                count = connection.scalar(text(f"SELECT COUNT(*) FROM {spec.table}"))
                available[spec.table] = int(count or 0)
        return LegacyMigrationPreview(
            destination_account_id=destination_account_id,
            available=available,
            missing=tuple(missing),
            excluded_present=tuple(table for table in EXCLUDED_TABLES if table in names),
            total_rows=sum(available.values()),
        )

    def import_selected(
        self,
        source_engine: Engine,
        destination: Session,
        destination_account_id: UUID,
        *,
        original_backup_confirmed: bool,
    ) -> LegacyMigrationResult:
        if not original_backup_confirmed:
            raise ValueError("원본 DB 백업 확인 없이는 이관을 실행할 수 없습니다.")
        account = destination.get(AccountRecord, str(destination_account_id))
        if account is None:
            raise KeyError("이관 대상 v2 계좌를 찾을 수 없습니다.")

        preview = self.preview(source_engine, destination_account_id)
        imported: dict[str, int] = {}
        skipped = 0
        with source_engine.connect() as connection:
            for spec in SELECTED_TABLES:
                if spec.table not in preview.available:
                    continue
                rows = connection.execute(text(f"SELECT * FROM {spec.table}")).mappings()
                imported_count = 0
                for index, row in enumerate(rows):
                    source_id = self._source_id(dict(row), spec, index)
                    exists = destination.scalar(
                        select(LegacyHistoryRecord.id).where(
                            LegacyHistoryRecord.source_table == spec.table,
                            LegacyHistoryRecord.source_row_id == source_id,
                        )
                    )
                    if exists:
                        skipped += 1
                        continue
                    destination.add(
                        LegacyHistoryRecord(
                            account_id=str(destination_account_id),
                            history_kind=spec.history_kind,
                            source_table=spec.table,
                            source_row_id=source_id,
                            source_payload=self._json_payload(dict(row)),
                            read_only=True,
                        )
                    )
                    imported_count += 1
                imported[spec.table] = imported_count
        destination.commit()
        return LegacyMigrationResult(imported=imported, skipped_duplicates=skipped)

    @staticmethod
    def _source_id(row: dict[str, Any], spec: LegacyTableSpec, index: int) -> str:
        for column in spec.default_id_columns:
            value = row.get(column)
            if value is not None:
                return str(value)
        return f"row-{index}"

    @staticmethod
    def _json_payload(row: dict[str, Any]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in row.items():
            if isinstance(value, (datetime, date)):
                result[key] = value.isoformat()
            elif isinstance(value, Decimal):
                result[key] = str(value)
            elif isinstance(value, bytes):
                result[key] = value.hex()
            else:
                result[key] = value
        return result
