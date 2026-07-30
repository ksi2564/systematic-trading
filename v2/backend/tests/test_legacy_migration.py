from uuid import UUID

from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session

from wallant.migration.legacy import LegacyMigrationService
from wallant.persistence.models import AccountRecord, Base, LegacyHistoryRecord


def test_선택한_이력만_읽기전용으로_한_계좌에_이관한다(tmp_path) -> None:
    source = create_engine(f"sqlite+pysqlite:///{tmp_path / 'legacy.db'}")
    with source.begin() as connection:
        connection.execute(text("CREATE TABLE strategy_state (id INTEGER PRIMARY KEY, phase TEXT)"))
        connection.execute(text("INSERT INTO strategy_state VALUES (1, 'NORMAL')"))
        connection.execute(text("CREATE TABLE operation_mode_audit (id INTEGER PRIMARY KEY)"))
        connection.execute(text("INSERT INTO operation_mode_audit VALUES (1)"))

    destination = create_engine(f"sqlite+pysqlite:///{tmp_path / 'v2.db'}")
    Base.metadata.create_all(destination)
    account_id = UUID("00000000-0000-0000-0000-000000000001")
    with Session(destination) as session:
        session.add(
            AccountRecord(
                id=str(account_id),
                name="이관 계좌",
                market="US",
                currency="USD",
                status="PAUSED",
            )
        )
        session.commit()
        preview = LegacyMigrationService().preview(source, account_id)
        assert preview.total_rows == 1
        assert preview.excluded_present == ("operation_mode_audit",)

        result = LegacyMigrationService().import_selected(
            source,
            session,
            account_id,
            original_backup_confirmed=True,
        )
        assert result.imported["strategy_state"] == 1
        row = session.query(LegacyHistoryRecord).one()
        assert row.account_id == str(account_id)
        assert row.read_only
    source.dispose()
    destination.dispose()
