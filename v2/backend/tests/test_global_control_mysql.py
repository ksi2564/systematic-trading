from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import sessionmaker

from wallant.config import get_settings
from wallant.persistence.models import AuditEventRecord, Base, GlobalControlRecord
from wallant.persistence.repositories import GlobalControlRepository

MYSQL_TEST_URL = os.getenv("WALLANT_MYSQL_TEST_URL")
pytestmark = pytest.mark.skipif(
    not MYSQL_TEST_URL,
    reason="WALLANT_MYSQL_TEST_URL이 있을 때만 운영 dialect 통합 검증을 실행합니다.",
)


@pytest.fixture
def mysql_engine():
    assert MYSQL_TEST_URL is not None
    engine = create_engine(MYSQL_TEST_URL, pool_pre_ping=True)
    with engine.begin() as connection:
        connection.execute(text("SET FOREIGN_KEY_CHECKS=0"))
        Base.metadata.drop_all(connection)
        connection.execute(text("DROP TABLE IF EXISTS alembic_version"))
        connection.execute(text("SET FOREIGN_KEY_CHECKS=1"))
    try:
        yield engine
    finally:
        with engine.begin() as connection:
            connection.execute(text("SET FOREIGN_KEY_CHECKS=0"))
            Base.metadata.drop_all(connection)
            connection.execute(text("DROP TABLE IF EXISTS alembic_version"))
            connection.execute(text("SET FOREIGN_KEY_CHECKS=1"))
        engine.dispose()


def test_mysql_최초_pause_resume_동시_쓰기는_단일행과_감사를_직렬화한다(mysql_engine) -> None:
    Base.metadata.create_all(mysql_engine)
    sessions = sessionmaker(bind=mysql_engine, autoflush=False, expire_on_commit=False)
    barrier = Barrier(3)

    def write(action: str) -> str:
        with sessions() as session:
            barrier.wait(timeout=10)
            repository = GlobalControlRepository(session)
            if action == "pause":
                repository.pause("MySQL 동시 안전 정지", actor="pause-worker")
            else:
                repository.resume(actor="resume-worker", confirmed=True)
            return action

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(write, action) for action in ("pause", "resume")]
        barrier.wait(timeout=10)
        assert sorted(future.result(timeout=30) for future in futures) == ["pause", "resume"]

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 1
        actions = sorted(session.scalars(select(AuditEventRecord.action)).all())
        assert actions == ["GLOBAL_PAUSED", "GLOBAL_RESUMED"]


def test_mysql_감사_insert_실패는_제어행까지_rollback한다(mysql_engine) -> None:
    Base.metadata.create_all(mysql_engine)
    sessions = sessionmaker(bind=mysql_engine, autoflush=False, expire_on_commit=False)
    with mysql_engine.begin() as connection:
        connection.execute(
            text(
                """
                ALTER TABLE v2_audit_event
                ADD CONSTRAINT wallant_fail_audit_insert
                CHECK (action <> 'GLOBAL_PAUSED')
                """
            )
        )

    with sessions() as session:
        with pytest.raises(Exception, match="wallant_fail_audit_insert"):
            GlobalControlRepository(session).pause("rollback", actor="tester")
        session.rollback()

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 0
        assert session.scalar(select(func.count()).select_from(AuditEventRecord)) == 0


def test_mysql_migration_seed와_downgrade_reupgrade가_정지상태를_보존한다(
    mysql_engine,
    monkeypatch,
) -> None:
    assert MYSQL_TEST_URL is not None
    monkeypatch.setenv("WALLANT_DATABASE_URL", MYSQL_TEST_URL)
    monkeypatch.setenv("WALLANT_ENVIRONMENT", "test")
    get_settings.cache_clear()
    backend_root = Path(__file__).resolve().parents[1]
    config = Config(str(backend_root / "alembic.ini"))

    try:
        command.upgrade(config, "head")
        with mysql_engine.begin() as connection:
            row = connection.execute(
                text(
                    "SELECT emergency_paused, reason FROM v2_global_control "
                    "WHERE singleton_key = 'GLOBAL'"
                )
            ).one()
            assert tuple(row) == (False, None)
            connection.execute(
                text(
                    "UPDATE v2_global_control SET emergency_paused = 1, reason = 'preserve' "
                    "WHERE singleton_key = 'GLOBAL'"
                )
            )

        command.downgrade(config, "0001")
        command.upgrade(config, "head")
        with mysql_engine.connect() as connection:
            row = connection.execute(
                text(
                    "SELECT emergency_paused, reason FROM v2_global_control "
                    "WHERE singleton_key = 'GLOBAL'"
                )
            ).one()
            assert tuple(row) == (True, "preserve")
    finally:
        get_settings.cache_clear()
