"""전역 안전 제어 단일 행 생성

Revision ID: 0002
Revises: 0001
"""

from __future__ import annotations

from datetime import UTC, datetime

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.mysql import insert as mysql_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    control = sa.table(
        "v2_global_control",
        sa.column("singleton_key", sa.String(20)),
        sa.column("emergency_paused", sa.Boolean()),
        sa.column("reason", sa.Text()),
        sa.column("updated_at", sa.DateTime(timezone=True)),
    )
    values = {
        "singleton_key": "GLOBAL",
        "emergency_paused": False,
        "reason": None,
        "updated_at": datetime.now(UTC),
    }
    connection = op.get_bind()
    if connection.dialect.name == "mysql":
        statement = mysql_insert(control).values(**values)
        statement = statement.on_duplicate_key_update(
            singleton_key=statement.inserted.singleton_key
        )
    elif connection.dialect.name == "sqlite":
        statement = sqlite_insert(control).values(**values).on_conflict_do_nothing(
            index_elements=["singleton_key"]
        )
    else:
        raise RuntimeError(
            f"지원하지 않는 전역 제어 DB dialect입니다: {connection.dialect.name}"
        )
    connection.execute(statement)


def downgrade() -> None:
    # 안전 상태를 잃을 수 있으므로 제어 행을 삭제하지 않는다.
    pass
