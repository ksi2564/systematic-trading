from __future__ import annotations

from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any
from uuid import uuid4

from sqlalchemy import (
    JSON,
    Boolean,
    Date,
    DateTime,
    ForeignKey,
    Index,
    LargeBinary,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utc_now() -> datetime:
    return datetime.now(UTC)


def new_id() -> str:
    return str(uuid4())


class Base(DeclarativeBase):
    pass


class StrategyRecord(Base):
    __tablename__ = "v2_strategy"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=utc_now,
        onupdate=utc_now,
        nullable=False,
    )
    versions: Mapped[list[StrategyVersionRecord]] = relationship(
        back_populates="strategy",
        cascade="all, delete-orphan",
        order_by="StrategyVersionRecord.version",
    )


class StrategyVersionRecord(Base):
    __tablename__ = "v2_strategy_version"
    __table_args__ = (
        UniqueConstraint("strategy_id", "version", name="uq_v2_strategy_version_number"),
        UniqueConstraint("strategy_id", "checksum", name="uq_v2_strategy_version_checksum"),
        Index("ix_v2_strategy_version_lifecycle", "lifecycle"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    strategy_id: Mapped[str] = mapped_column(
        ForeignKey("v2_strategy.id", ondelete="CASCADE"),
        nullable=False,
    )
    version: Mapped[int] = mapped_column(nullable=False)
    lifecycle: Mapped[str] = mapped_column(String(32), nullable=False, default="DRAFT")
    checksum: Mapped[str] = mapped_column(String(64), nullable=False)
    definition: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    approved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    strategy: Mapped[StrategyRecord] = relationship(back_populates="versions")


class AccountRecord(Base):
    __tablename__ = "v2_account"
    __table_args__ = (UniqueConstraint("name", name="uq_v2_account_name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    broker: Mapped[str] = mapped_column(String(20), nullable=False, default="KIS")
    market: Mapped[str] = mapped_column(String(10), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="PAUSED")
    status_reason: Mapped[str | None] = mapped_column(Text)
    active_strategy_version_id: Mapped[str | None] = mapped_column(
        ForeignKey("v2_strategy_version.id", ondelete="SET NULL")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=utc_now,
        onupdate=utc_now,
        nullable=False,
    )
    execution_profile: Mapped[ExecutionProfileRecord | None] = relationship(
        back_populates="account",
        cascade="all, delete-orphan",
        uselist=False,
    )
    risk_policy: Mapped[RiskPolicyRecord | None] = relationship(
        back_populates="account",
        cascade="all, delete-orphan",
        uselist=False,
    )


class ExecutionProfileRecord(Base):
    __tablename__ = "v2_execution_profile"

    account_id: Mapped[str] = mapped_column(
        ForeignKey("v2_account.id", ondelete="CASCADE"),
        primary_key=True,
    )
    order_type: Mapped[str] = mapped_column(String(20), nullable=False, default="LIMIT")
    schedule: Mapped[str] = mapped_column(String(40), nullable=False, default="REGULAR_SESSION")
    slices: Mapped[int] = mapped_column(nullable=False, default=1)
    max_reprice_attempts: Mapped[int] = mapped_column(nullable=False, default=2)
    reprice_ticks: Mapped[int] = mapped_column(nullable=False, default=1)
    fee_rate_pct: Mapped[Decimal] = mapped_column(Numeric(10, 4), nullable=False, default=Decimal("0.25"))
    fx_mode: Mapped[str] = mapped_column(String(20), nullable=False, default="MANUAL")
    max_auto_fx_amount: Mapped[Decimal | None] = mapped_column(Numeric(20, 4))
    account: Mapped[AccountRecord] = relationship(back_populates="execution_profile")


class RiskPolicyRecord(Base):
    __tablename__ = "v2_risk_policy"

    account_id: Mapped[str] = mapped_column(
        ForeignKey("v2_account.id", ondelete="CASCADE"),
        primary_key=True,
    )
    max_order_notional: Mapped[Decimal] = mapped_column(Numeric(20, 4), nullable=False)
    max_daily_notional: Mapped[Decimal] = mapped_column(Numeric(20, 4), nullable=False)
    max_daily_order_count: Mapped[int] = mapped_column(nullable=False)
    max_symbol_weight_pct: Mapped[Decimal] = mapped_column(Numeric(10, 4), nullable=False)
    max_daily_loss: Mapped[Decimal] = mapped_column(Numeric(20, 4), nullable=False)
    max_reprice_attempts: Mapped[int] = mapped_column(nullable=False)
    account: Mapped[AccountRecord] = relationship(back_populates="risk_policy")


class BrokerCredentialRecord(Base):
    __tablename__ = "v2_broker_credential"

    account_id: Mapped[str] = mapped_column(
        ForeignKey("v2_account.id", ondelete="CASCADE"),
        primary_key=True,
    )
    key_version: Mapped[int] = mapped_column(nullable=False, default=1)
    nonce: Mapped[bytes] = mapped_column(LargeBinary(12), nullable=False)
    ciphertext: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    masked_account: Mapped[str] = mapped_column(String(40), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=utc_now,
        onupdate=utc_now,
        nullable=False,
    )


class StrategyRunRecord(Base):
    __tablename__ = "v2_strategy_run"
    __table_args__ = (Index("ix_v2_strategy_run_version_type", "strategy_version_id", "run_type"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    strategy_version_id: Mapped[str] = mapped_column(
        ForeignKey("v2_strategy_version.id", ondelete="CASCADE"),
        nullable=False,
    )
    run_type: Mapped[str] = mapped_column(String(30), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    start_date: Mapped[date | None] = mapped_column(Date)
    end_date: Mapped[date | None] = mapped_column(Date)
    summary: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    evidence: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class StrategyStateRecord(Base):
    __tablename__ = "v2_strategy_state"
    __table_args__ = (
        UniqueConstraint(
            "account_id",
            "strategy_version_id",
            "as_of_date",
            name="uq_v2_strategy_state_day",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    account_id: Mapped[str | None] = mapped_column(ForeignKey("v2_account.id", ondelete="CASCADE"))
    strategy_version_id: Mapped[str] = mapped_column(
        ForeignKey("v2_strategy_version.id", ondelete="CASCADE"),
        nullable=False,
    )
    as_of_date: Mapped[date] = mapped_column(Date, nullable=False)
    state: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    target_weights: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    explanation: Mapped[list[str]] = mapped_column(JSON, nullable=False, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)


class OrderIntentRecord(Base):
    __tablename__ = "v2_order_intent"
    __table_args__ = (
        UniqueConstraint("idempotency_key", name="uq_v2_order_intent_idempotency"),
        Index("ix_v2_order_intent_account_signal", "account_id", "signal_date"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    idempotency_key: Mapped[str] = mapped_column(String(64), nullable=False)
    account_id: Mapped[str | None] = mapped_column(
        ForeignKey("v2_account.id", ondelete="CASCADE"),
        nullable=True,
    )
    strategy_version_id: Mapped[str] = mapped_column(
        ForeignKey("v2_strategy_version.id", ondelete="CASCADE"),
        nullable=False,
    )
    signal_date: Mapped[date] = mapped_column(Date, nullable=False)
    symbol: Mapped[str] = mapped_column(String(20), nullable=False)
    side: Mapped[str] = mapped_column(String(10), nullable=False)
    quantity: Mapped[int] = mapped_column(nullable=False)
    reference_price: Mapped[Decimal] = mapped_column(Numeric(20, 6), nullable=False)
    target_weight_pct: Mapped[Decimal] = mapped_column(Numeric(10, 4), nullable=False)
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)


class MarketDataCatalogRecord(Base):
    __tablename__ = "v2_market_data_catalog"
    __table_args__ = (
        UniqueConstraint(
            "symbol",
            "resolution",
            "provider",
            "file_path",
            name="uq_v2_market_data_catalog_file",
        ),
        Index("ix_v2_market_data_lookup", "symbol", "resolution", "start_date", "end_date"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    symbol: Mapped[str] = mapped_column(String(20), nullable=False)
    market: Mapped[str] = mapped_column(String(10), nullable=False)
    resolution: Mapped[str] = mapped_column(String(10), nullable=False)
    provider: Mapped[str] = mapped_column(String(100), nullable=False)
    official: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    file_path: Mapped[str] = mapped_column(String(500), nullable=False)
    start_date: Mapped[date] = mapped_column(Date, nullable=False)
    end_date: Mapped[date] = mapped_column(Date, nullable=False)
    row_count: Mapped[int] = mapped_column(nullable=False)
    checksum: Mapped[str] = mapped_column(String(64), nullable=False)
    warning: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)


class AuditEventRecord(Base):
    __tablename__ = "v2_audit_event"
    __table_args__ = (Index("ix_v2_audit_event_created", "created_at"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    actor: Mapped[str] = mapped_column(String(200), nullable=False)
    action: Mapped[str] = mapped_column(String(100), nullable=False)
    resource_type: Mapped[str] = mapped_column(String(50), nullable=False)
    resource_id: Mapped[str | None] = mapped_column(String(100))
    details: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)


class GlobalControlRecord(Base):
    __tablename__ = "v2_global_control"

    singleton_key: Mapped[str] = mapped_column(String(20), primary_key=True, default="GLOBAL")
    emergency_paused: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    reason: Mapped[str | None] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=utc_now,
        onupdate=utc_now,
        nullable=False,
    )


class LegacyHistoryRecord(Base):
    __tablename__ = "v2_legacy_history"
    __table_args__ = (
        UniqueConstraint(
            "source_table",
            "source_row_id",
            name="uq_v2_legacy_history_source",
        ),
        Index("ix_v2_legacy_history_account_kind", "account_id", "history_kind"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    account_id: Mapped[str] = mapped_column(
        ForeignKey("v2_account.id", ondelete="CASCADE"),
        nullable=False,
    )
    history_kind: Mapped[str] = mapped_column(String(40), nullable=False)
    source_table: Mapped[str] = mapped_column(String(80), nullable=False)
    source_row_id: Mapped[str] = mapped_column(String(100), nullable=False)
    source_payload: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    read_only: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    imported_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
