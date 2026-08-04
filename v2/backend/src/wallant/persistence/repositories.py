from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from wallant.domain.execution import RiskPolicy
from wallant.domain.strategy import (
    StrategyDefinition,
    StrategyLifecycle,
    StrategyVersion,
)
from wallant.persistence.models import (
    AccountRecord,
    AuditEventRecord,
    GlobalControlRecord,
    RiskPolicyRecord,
    StrategyRecord,
    StrategyRunRecord,
    StrategyVersionRecord,
)


class DuplicateStrategyVersionError(ValueError):
    pass


class InvalidLifecycleTransitionError(ValueError):
    pass


ALLOWED_TRANSITIONS: dict[StrategyLifecycle, set[StrategyLifecycle]] = {
    StrategyLifecycle.DRAFT: {StrategyLifecycle.BACKTESTED, StrategyLifecycle.RETIRED},
    StrategyLifecycle.BACKTESTED: {StrategyLifecycle.ROLLING_VALIDATED, StrategyLifecycle.RETIRED},
    StrategyLifecycle.ROLLING_VALIDATED: {StrategyLifecycle.PAPER, StrategyLifecycle.RETIRED},
    StrategyLifecycle.PAPER: {StrategyLifecycle.LIVE_APPROVED, StrategyLifecycle.RETIRED},
    StrategyLifecycle.LIVE_APPROVED: {StrategyLifecycle.RETIRED},
    StrategyLifecycle.RETIRED: set(),
}

REQUIRED_COMPLETED_RUN: dict[StrategyLifecycle, str] = {
    StrategyLifecycle.BACKTESTED: "BACKTEST",
    StrategyLifecycle.ROLLING_VALIDATED: "ROLLING",
    StrategyLifecycle.LIVE_APPROVED: "PAPER",
}


class StrategyRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def list(self) -> list[StrategyRecord]:
        statement = select(StrategyRecord).options(selectinload(StrategyRecord.versions))
        return list(self.session.scalars(statement.order_by(StrategyRecord.created_at.desc())))

    def create(self, definition: StrategyDefinition, *, actor: str = "system") -> StrategyVersion:
        strategy_id = uuid4()
        strategy = StrategyRecord(
            id=str(strategy_id),
            name=definition.name,
            description=definition.description,
        )
        version = StrategyVersion(strategy_id=strategy_id, definition=definition)
        record = self._version_record(version)
        strategy.versions.append(record)
        self.session.add(strategy)
        self.session.add(
            AuditEventRecord(
                actor=actor,
                action="STRATEGY_CREATED",
                resource_type="STRATEGY_VERSION",
                resource_id=str(version.id),
                details={"strategy_id": str(strategy_id), "version": version.version},
            )
        )
        try:
            self.session.commit()
        except IntegrityError as exc:
            self.session.rollback()
            raise DuplicateStrategyVersionError("동일한 전략 버전이 이미 존재합니다.") from exc
        return self._domain(record)

    def add_version(
        self,
        strategy_id: UUID,
        definition: StrategyDefinition,
        *,
        actor: str = "system",
    ) -> StrategyVersion:
        strategy = self.session.get(StrategyRecord, str(strategy_id))
        if strategy is None:
            raise KeyError("전략을 찾을 수 없습니다.")
        latest = self.session.scalar(
            select(StrategyVersionRecord)
            .where(StrategyVersionRecord.strategy_id == str(strategy_id))
            .order_by(StrategyVersionRecord.version.desc())
        )
        number = 1 if latest is None else latest.version + 1
        version = StrategyVersion(
            strategy_id=strategy_id,
            version=number,
            definition=definition,
        )
        record = self._version_record(version)
        self.session.add(record)
        self.session.add(
            AuditEventRecord(
                actor=actor,
                action="STRATEGY_VERSION_CREATED",
                resource_type="STRATEGY_VERSION",
                resource_id=str(version.id),
                details={"strategy_id": str(strategy_id), "version": number},
            )
        )
        try:
            self.session.commit()
        except IntegrityError as exc:
            self.session.rollback()
            raise DuplicateStrategyVersionError("동일한 정의의 전략 버전이 이미 존재합니다.") from exc
        return self._domain(record)

    def get_version(self, version_id: UUID) -> StrategyVersion:
        record = self.session.get(StrategyVersionRecord, str(version_id))
        if record is None:
            raise KeyError("전략 버전을 찾을 수 없습니다.")
        return self._domain(record)

    def transition(
        self,
        version_id: UUID,
        target: StrategyLifecycle,
        *,
        actor: str,
        evidence: dict | None = None,
    ) -> StrategyVersion:
        record = self.session.get(StrategyVersionRecord, str(version_id))
        if record is None:
            raise KeyError("전략 버전을 찾을 수 없습니다.")
        current = StrategyLifecycle(record.lifecycle)
        if target not in ALLOWED_TRANSITIONS[current]:
            raise InvalidLifecycleTransitionError(
                f"{current.value}에서 {target.value}(으)로 전환할 수 없습니다."
            )
        required_run = REQUIRED_COMPLETED_RUN.get(target)
        if required_run and not self.session.scalar(
            select(StrategyRunRecord.id)
            .where(
                StrategyRunRecord.strategy_version_id == record.id,
                StrategyRunRecord.run_type == required_run,
                StrategyRunRecord.status == "COMPLETED",
            )
            .limit(1)
        ):
            raise InvalidLifecycleTransitionError(
                f"{target.value} 전환에는 완료된 {required_run} 실행 기록이 필요합니다."
            )
        record.lifecycle = target.value
        if target == StrategyLifecycle.LIVE_APPROVED:
            record.approved_at = datetime.now(UTC)
        self.session.add(
            AuditEventRecord(
                actor=actor,
                action="STRATEGY_LIFECYCLE_TRANSITION",
                resource_type="STRATEGY_VERSION",
                resource_id=record.id,
                details={
                    "from": current.value,
                    "to": target.value,
                    "evidence": evidence or {},
                },
            )
        )
        self.session.commit()
        return self._domain(record)

    @staticmethod
    def _version_record(version: StrategyVersion) -> StrategyVersionRecord:
        return StrategyVersionRecord(
            id=str(version.id),
            strategy_id=str(version.strategy_id),
            version=version.version,
            lifecycle=version.lifecycle.value,
            checksum=version.checksum,
            definition=version.definition.model_dump(mode="json"),
            created_at=version.created_at,
            approved_at=version.approved_at,
        )

    @staticmethod
    def _domain(record: StrategyVersionRecord) -> StrategyVersion:
        created_at = (
            record.created_at
            if record.created_at.tzinfo is not None
            else record.created_at.replace(tzinfo=UTC)
        )
        approved_at = (
            record.approved_at
            if record.approved_at is None or record.approved_at.tzinfo is not None
            else record.approved_at.replace(tzinfo=UTC)
        )
        return StrategyVersion(
            id=UUID(record.id),
            strategy_id=UUID(record.strategy_id),
            version=record.version,
            lifecycle=StrategyLifecycle(record.lifecycle),
            definition=StrategyDefinition.model_validate(record.definition),
            checksum=record.checksum,
            created_at=created_at,
            approved_at=approved_at,
        )


class AccountRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def list(self) -> list[AccountRecord]:
        statement = (
            select(AccountRecord)
            .options(
                selectinload(AccountRecord.risk_policy),
            )
            .order_by(AccountRecord.created_at)
        )
        return list(self.session.scalars(statement))

    def create(
        self,
        *,
        name: str,
        market: str,
        currency: str,
        risk_policy: RiskPolicy,
        actor: str = "system",
    ) -> AccountRecord:
        account = AccountRecord(
            name=name.strip(),
            market=market.strip().upper(),
            currency=currency.strip().upper(),
            status="PAUSED",
            status_reason="신규 계좌는 명시적으로 활성화해야 합니다.",
        )
        account.risk_policy = RiskPolicyRecord(**risk_policy.model_dump())
        self.session.add(account)
        self.session.flush()
        self._audit(actor, "ACCOUNT_CREATED", account.id, {"market": market, "currency": currency})
        self.session.commit()
        return account

    def assign_strategy(self, account_id: UUID, strategy_version_id: UUID, *, actor: str) -> AccountRecord:
        account = self._get(account_id, for_update=True)
        version = self.session.get(StrategyVersionRecord, str(strategy_version_id))
        if version is None:
            raise KeyError("전략 버전을 찾을 수 없습니다.")
        if version.lifecycle != StrategyLifecycle.LIVE_APPROVED.value:
            raise ValueError("LIVE_APPROVED 전략 버전만 계좌에 할당할 수 있습니다.")
        strategy_market = str(version.definition.get("market", "")).upper()
        if strategy_market != account.market:
            raise ValueError(
                f"{account.market} 계좌에 {strategy_market or '미지정'} 시장 전략을 할당할 수 없습니다."
            )
        if account.status == "ACTIVE":
            if account.active_strategy_version_id == version.id:
                return account
            raise ValueError("활성 계좌의 전략을 바꾸기 전에 계좌를 정지해야 합니다.")
        account.active_strategy_version_id = version.id
        self._audit(actor, "ACCOUNT_STRATEGY_ASSIGNED", account.id, {"strategy_version_id": version.id})
        self.session.commit()
        return account

    def pause(self, account_id: UUID, reason: str, *, actor: str) -> AccountRecord:
        account = self._get(account_id, for_update=True)
        account.status = "PAUSED"
        account.status_reason = reason.strip()
        self._audit(actor, "ACCOUNT_PAUSED", account.id, {"reason": account.status_reason})
        self.session.commit()
        return account

    def resume(self, account_id: UUID, *, actor: str, confirmed: bool) -> AccountRecord:
        if not confirmed:
            raise ValueError("계좌 재개에는 명시적인 확인이 필요합니다.")
        account = self._get(account_id, for_update=True)
        if account.active_strategy_version_id is None:
            raise ValueError("활성 전략이 없는 계좌는 재개할 수 없습니다.")
        version = self.session.get(StrategyVersionRecord, account.active_strategy_version_id)
        if version is None or version.lifecycle != StrategyLifecycle.LIVE_APPROVED.value:
            raise ValueError("LIVE_APPROVED 상태가 아닌 전략으로 계좌를 재개할 수 없습니다.")
        if account.risk_policy is None:
            raise ValueError("위험 한도가 없는 계좌는 재개할 수 없습니다.")
        account.status = "ACTIVE"
        account.status_reason = None
        self._audit(actor, "ACCOUNT_RESUMED", account.id, {})
        self.session.commit()
        return account

    def _get(self, account_id: UUID, *, for_update: bool = False) -> AccountRecord:
        statement = (
            select(AccountRecord)
            .where(AccountRecord.id == str(account_id))
            .options(
                selectinload(AccountRecord.risk_policy),
            )
        )
        if for_update:
            statement = statement.with_for_update()
        account = self.session.scalar(statement)
        if account is None:
            raise KeyError("계좌를 찾을 수 없습니다.")
        return account

    def _audit(self, actor: str, action: str, resource_id: str, details: dict) -> None:
        self.session.add(
            AuditEventRecord(
                actor=actor,
                action=action,
                resource_type="ACCOUNT",
                resource_id=resource_id,
                details=details,
            )
        )


class GlobalControlRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self) -> GlobalControlRecord:
        control = self.session.get(GlobalControlRecord, "GLOBAL")
        if control is None:
            control = GlobalControlRecord(singleton_key="GLOBAL")
            self.session.add(control)
            self.session.commit()
        return control

    def pause(self, reason: str, *, actor: str) -> GlobalControlRecord:
        control = self.get()
        control.emergency_paused = True
        control.reason = reason.strip()
        self.session.add(
            AuditEventRecord(
                actor=actor,
                action="GLOBAL_PAUSED",
                resource_type="GLOBAL_CONTROL",
                resource_id="GLOBAL",
                details={"reason": control.reason},
            )
        )
        self.session.commit()
        return control

    def resume(self, *, actor: str, confirmed: bool) -> GlobalControlRecord:
        if not confirmed:
            raise ValueError("전체 재개에는 명시적인 확인이 필요합니다.")
        control = self.get()
        control.emergency_paused = False
        control.reason = None
        self.session.add(
            AuditEventRecord(
                actor=actor,
                action="GLOBAL_RESUMED",
                resource_type="GLOBAL_CONTROL",
                resource_id="GLOBAL",
                details={},
            )
        )
        self.session.commit()
        return control
