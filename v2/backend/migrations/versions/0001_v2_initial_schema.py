"""v2 초기 스키마

Revision ID: 0001
Revises:
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "v2_strategy",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "v2_strategy_version",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("strategy_id", sa.String(36), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("lifecycle", sa.String(32), nullable=False),
        sa.Column("checksum", sa.String(64), nullable=False),
        sa.Column("definition", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("approved_at", sa.DateTime(timezone=True)),
        sa.ForeignKeyConstraint(["strategy_id"], ["v2_strategy.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("strategy_id", "version", name="uq_v2_strategy_version_number"),
        sa.UniqueConstraint("strategy_id", "checksum", name="uq_v2_strategy_version_checksum"),
    )
    op.create_index(
        "ix_v2_strategy_version_lifecycle",
        "v2_strategy_version",
        ["lifecycle"],
    )
    op.create_table(
        "v2_account",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("broker", sa.String(20), nullable=False),
        sa.Column("market", sa.String(10), nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("status_reason", sa.Text()),
        sa.Column("active_strategy_version_id", sa.String(36)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["active_strategy_version_id"],
            ["v2_strategy_version.id"],
            ondelete="SET NULL",
        ),
        sa.UniqueConstraint("name", name="uq_v2_account_name"),
    )
    op.create_table(
        "v2_risk_policy",
        sa.Column("account_id", sa.String(36), primary_key=True),
        sa.Column("max_order_notional", sa.Numeric(20, 4), nullable=False),
        sa.Column("max_daily_notional", sa.Numeric(20, 4), nullable=False),
        sa.Column("max_daily_order_count", sa.Integer(), nullable=False),
        sa.Column("max_symbol_weight_pct", sa.Numeric(10, 4), nullable=False),
        sa.Column("max_daily_loss", sa.Numeric(20, 4), nullable=False),
        sa.ForeignKeyConstraint(["account_id"], ["v2_account.id"], ondelete="CASCADE"),
    )
    op.create_table(
        "v2_daily_risk_usage",
        sa.Column("account_id", sa.String(36), primary_key=True),
        sa.Column("usage_date", sa.Date(), primary_key=True),
        sa.Column("order_notional", sa.Numeric(20, 4), nullable=False),
        sa.Column("order_count", sa.Integer(), nullable=False),
        sa.Column("realized_loss", sa.Numeric(20, 4), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["account_id"], ["v2_account.id"], ondelete="CASCADE"),
    )
    op.create_table(
        "v2_broker_credential",
        sa.Column("account_id", sa.String(36), primary_key=True),
        sa.Column("key_version", sa.Integer(), nullable=False),
        sa.Column("nonce", sa.LargeBinary(12), nullable=False),
        sa.Column("ciphertext", sa.LargeBinary(), nullable=False),
        sa.Column("masked_account", sa.String(40), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["account_id"], ["v2_account.id"], ondelete="CASCADE"),
    )
    op.create_table(
        "v2_strategy_run",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("strategy_version_id", sa.String(36), nullable=False),
        sa.Column("run_type", sa.String(30), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("start_date", sa.Date()),
        sa.Column("end_date", sa.Date()),
        sa.Column("summary", sa.JSON(), nullable=False),
        sa.Column("evidence", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True)),
        sa.ForeignKeyConstraint(
            ["strategy_version_id"],
            ["v2_strategy_version.id"],
            ondelete="CASCADE",
        ),
    )
    op.create_index(
        "ix_v2_strategy_run_version_type",
        "v2_strategy_run",
        ["strategy_version_id", "run_type"],
    )
    op.create_table(
        "v2_strategy_state",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("paper_session_id", sa.String(36)),
        sa.Column("account_id", sa.String(36)),
        sa.Column("strategy_version_id", sa.String(36), nullable=False),
        sa.Column("as_of_date", sa.Date(), nullable=False),
        sa.Column("state", sa.JSON(), nullable=False),
        sa.Column("target_weights", sa.JSON(), nullable=False),
        sa.Column("explanation", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["paper_session_id"],
            ["v2_strategy_run.id"],
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(["account_id"], ["v2_account.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["strategy_version_id"],
            ["v2_strategy_version.id"],
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint(
            "paper_session_id",
            "as_of_date",
            name="uq_v2_strategy_state_paper_day",
        ),
    )
    op.create_table(
        "v2_order_intent",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("idempotency_key", sa.String(64), nullable=False),
        sa.Column("paper_session_id", sa.String(36)),
        sa.Column("account_id", sa.String(36), nullable=True),
        sa.Column("strategy_version_id", sa.String(36), nullable=False),
        sa.Column("signal_date", sa.Date(), nullable=False),
        sa.Column("symbol", sa.String(20), nullable=False),
        sa.Column("side", sa.String(10), nullable=False),
        sa.Column("quantity", sa.Integer(), nullable=False),
        sa.Column("reference_price", sa.Numeric(20, 6), nullable=False),
        sa.Column("target_weight_pct", sa.Numeric(10, 4), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["paper_session_id"],
            ["v2_strategy_run.id"],
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(["account_id"], ["v2_account.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["strategy_version_id"],
            ["v2_strategy_version.id"],
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint("idempotency_key", name="uq_v2_order_intent_idempotency"),
    )
    op.create_index(
        "ix_v2_order_intent_account_signal",
        "v2_order_intent",
        ["account_id", "signal_date"],
    )
    op.create_index(
        "ix_v2_order_intent_paper_session",
        "v2_order_intent",
        ["paper_session_id"],
    )
    op.create_table(
        "v2_market_data_catalog",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("symbol", sa.String(20), nullable=False),
        sa.Column("market", sa.String(10), nullable=False),
        sa.Column("resolution", sa.String(10), nullable=False),
        sa.Column("provider", sa.String(100), nullable=False),
        sa.Column("official", sa.Boolean(), nullable=False),
        sa.Column("file_path", sa.String(500), nullable=False),
        sa.Column("start_date", sa.Date(), nullable=False),
        sa.Column("end_date", sa.Date(), nullable=False),
        sa.Column("row_count", sa.Integer(), nullable=False),
        sa.Column("checksum", sa.String(64), nullable=False),
        sa.Column("warning", sa.Text()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "symbol",
            "resolution",
            "provider",
            "file_path",
            name="uq_v2_market_data_catalog_file",
        ),
    )
    op.create_index(
        "ix_v2_market_data_lookup",
        "v2_market_data_catalog",
        ["symbol", "resolution", "start_date", "end_date"],
    )
    op.create_table(
        "v2_audit_event",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("actor", sa.String(200), nullable=False),
        sa.Column("action", sa.String(100), nullable=False),
        sa.Column("resource_type", sa.String(50), nullable=False),
        sa.Column("resource_id", sa.String(100)),
        sa.Column("details", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_v2_audit_event_created", "v2_audit_event", ["created_at"])
    op.create_table(
        "v2_global_control",
        sa.Column("singleton_key", sa.String(20), primary_key=True),
        sa.Column("emergency_paused", sa.Boolean(), nullable=False),
        sa.Column("reason", sa.Text()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("v2_global_control")
    op.drop_index("ix_v2_audit_event_created", table_name="v2_audit_event")
    op.drop_table("v2_audit_event")
    op.drop_index("ix_v2_market_data_lookup", table_name="v2_market_data_catalog")
    op.drop_table("v2_market_data_catalog")
    op.drop_index("ix_v2_order_intent_paper_session", table_name="v2_order_intent")
    op.drop_index("ix_v2_order_intent_account_signal", table_name="v2_order_intent")
    op.drop_table("v2_order_intent")
    op.drop_table("v2_strategy_state")
    op.drop_index("ix_v2_strategy_run_version_type", table_name="v2_strategy_run")
    op.drop_table("v2_strategy_run")
    op.drop_table("v2_broker_credential")
    op.drop_table("v2_daily_risk_usage")
    op.drop_table("v2_risk_policy")
    op.drop_table("v2_account")
    op.drop_index("ix_v2_strategy_version_lifecycle", table_name="v2_strategy_version")
    op.drop_table("v2_strategy_version")
    op.drop_table("v2_strategy")
