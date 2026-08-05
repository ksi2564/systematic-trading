from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier

import pytest
from sqlalchemy import event, func, select
from sqlalchemy.orm import Session

from wallant.alerts.runner import DatabaseDiscordActions
from wallant.config import Settings
from wallant.persistence.database import create_database_engine, create_session_factory
from wallant.persistence.models import AuditEventRecord, Base, GlobalControlRecord
from wallant.persistence.repositories import GlobalControlRepository


def database(tmp_path: Path):
    settings = Settings(
        environment="test",
        build_sha="1" * 40,
        database_url=f"sqlite+pysqlite:///{tmp_path / 'control.db'}",
        parquet_root=tmp_path / "market",
        credential_master_key=None,
    )
    engine = create_database_engine(settings)
    Base.metadata.create_all(engine)
    return engine, create_session_factory(engine)


def test_read는_기본_autoflush_session에_pending이_있어도_DML을_실행하지_않는다(tmp_path) -> None:
    engine, _factory = database(tmp_path)
    statements: list[str] = []

    def capture_statement(_connection, _cursor, statement, _parameters, _context, _many) -> None:
        statements.append(statement.lstrip().split(None, 1)[0].upper())

    event.listen(engine, "before_cursor_execute", capture_statement)
    try:
        with Session(engine, autoflush=True) as session:
            session.add(
                AuditEventRecord(
                    actor="pending",
                    action="PENDING",
                    resource_type="TEST",
                    details={},
                )
            )
            assert GlobalControlRepository(session).read() is None
            assert "INSERT" not in statements
            session.rollback()
    finally:
        event.remove(engine, "before_cursor_execute", capture_statement)
        engine.dispose()


def test_discord_status는_제어행이_없어도_순수_조회다(tmp_path) -> None:
    engine, factory = database(tmp_path)
    try:
        message = asyncio.run(DatabaseDiscordActions(factory).status())
        assert "안전 제어 확인 불가(정지 취급)" in message
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 0
            assert session.scalar(select(func.count()).select_from(AuditEventRecord)) == 0
    finally:
        engine.dispose()


def test_pause_resume_동시_최초_쓰기는_단일행과_감사를_직렬화한다(tmp_path) -> None:
    engine, factory = database(tmp_path)
    barrier = Barrier(3)

    def write(action: str) -> str:
        with factory() as session:
            barrier.wait(timeout=5)
            repository = GlobalControlRepository(session)
            if action == "pause":
                repository.pause("동시 안전 정지", actor="pause-worker")
            else:
                repository.resume(actor="resume-worker", confirmed=True)
            return action

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(write, action) for action in ("pause", "resume")]
            barrier.wait(timeout=5)
            assert sorted(future.result(timeout=10) for future in futures) == ["pause", "resume"]

        with factory() as session:
            assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 1
            events = list(session.scalars(select(AuditEventRecord)))
            assert sorted(event.action for event in events) == ["GLOBAL_PAUSED", "GLOBAL_RESUMED"]
            assert session.get(GlobalControlRecord, "GLOBAL") is not None
    finally:
        engine.dispose()


def test_resume_확인_거부와_commit_실패는_제어·감사를_남기지_않는다(tmp_path) -> None:
    engine, factory = database(tmp_path)
    try:
        with factory() as session:
            with pytest.raises(ValueError, match="명시적인 확인"):
                GlobalControlRepository(session).resume(actor="tester", confirmed=False)
            session.rollback()

        def fail_commit(_session) -> None:
            raise RuntimeError("forced commit failure")

        with factory() as session:
            event.listen(session, "before_commit", fail_commit)
            with pytest.raises(RuntimeError, match="forced commit failure"):
                GlobalControlRepository(session).pause("롤백 검증", actor="tester")
            session.rollback()

        with factory() as session:
            assert session.scalar(select(func.count()).select_from(GlobalControlRecord)) == 0
            assert session.scalar(select(func.count()).select_from(AuditEventRecord)) == 0
    finally:
        engine.dispose()
