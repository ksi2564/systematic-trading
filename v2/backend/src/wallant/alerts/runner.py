from __future__ import annotations

from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from wallant.alerts.discord_gateway import DiscordGatewayActions, run_discord_gateway
from wallant.config import get_settings
from wallant.persistence.database import create_database_engine, create_session_factory
from wallant.persistence.models import AccountRecord, AuditEventRecord, OrderIntentRecord
from wallant.persistence.repositories import AccountRepository, GlobalControlRepository


class DatabaseDiscordActions(DiscordGatewayActions):
    def __init__(self, sessions: sessionmaker[Session] | None = None) -> None:
        self.engine = None
        if sessions is None:
            self.engine = create_database_engine()
            sessions = create_session_factory(self.engine)
        self.sessions = sessions

    async def status(self) -> str:
        with self.sessions() as session:
            control = GlobalControlRepository(session).read()
            accounts = session.scalar(select(func.count()).select_from(AccountRecord)) or 0
            paused = (
                session.scalar(
                    select(func.count()).select_from(AccountRecord).where(AccountRecord.status == "PAUSED")
                )
                or 0
            )
            intents = session.scalar(select(func.count()).select_from(OrderIntentRecord)) or 0
            if control is None:
                global_state = "안전 제어 확인 불가(정지 취급)"
            else:
                global_state = "정지" if control.emergency_paused else "정상"
            return (
                f"Wall-Ant: 전체 {global_state}, 계좌 {accounts}개 "
                f"(정지 {paused}개), 주문 의도 {intents}건, 실주문 비활성"
            )

    async def pause_account(self, account_id: str, reason: str, actor: str) -> str:
        with self.sessions() as session:
            record = AccountRepository(session).pause(UUID(account_id), reason, actor=actor)
            return f"{record.name} 계좌를 정지했습니다."

    async def pause_all(self, reason: str, actor: str) -> str:
        with self.sessions() as session:
            GlobalControlRepository(session).pause(reason, actor=actor)
            return "Wall-Ant 전체 긴급 정지를 활성화했습니다."

    async def audit_denied(self, command: str, actor: str, reason: str) -> None:
        with self.sessions() as session:
            session.add(
                AuditEventRecord(
                    actor=actor,
                    action="DISCORD_COMMAND_DENIED",
                    resource_type="DISCORD",
                    resource_id=command,
                    details={"reason": reason},
                )
            )
            session.commit()


def main() -> None:
    settings = get_settings()
    run_discord_gateway(settings, DatabaseDiscordActions())


if __name__ == "__main__":
    main()
