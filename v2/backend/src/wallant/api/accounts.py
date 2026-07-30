from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from wallant.api.dependencies import get_actor, get_session
from wallant.api.schemas import (
    AssignStrategyRequest,
    CreateAccountRequest,
    CredentialRequest,
    PauseRequest,
    ResumeRequest,
)
from wallant.api.serializers import account_record
from wallant.persistence.models import AccountRecord, AuditEventRecord, BrokerCredentialRecord
from wallant.persistence.repositories import AccountRepository
from wallant.security.credentials import CredentialCipher

router = APIRouter(prefix="/accounts", tags=["accounts"])


@router.get("")
def list_accounts(session: Session = Depends(get_session)) -> list[dict]:
    return [account_record(record) for record in AccountRepository(session).list()]


@router.post("", status_code=status.HTTP_201_CREATED)
def create_account(
    payload: CreateAccountRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        record = AccountRepository(session).create(
            name=payload.name,
            market=payload.market,
            currency=payload.currency,
            execution_profile=payload.execution_profile,
            risk_policy=payload.risk_policy,
            actor=actor,
        )
    except IntegrityError as exc:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="같은 이름의 계좌가 이미 존재합니다.",
        ) from exc
    return account_record(record)


@router.post("/{account_id}/strategy")
def assign_strategy(
    account_id: UUID,
    payload: AssignStrategyRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        return account_record(
            AccountRepository(session).assign_strategy(
                account_id,
                payload.strategy_version_id,
                actor=actor,
            )
        )
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.post("/{account_id}/pause")
def pause_account(
    account_id: UUID,
    payload: PauseRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        return account_record(AccountRepository(session).pause(account_id, payload.reason, actor=actor))
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.post("/{account_id}/resume")
def resume_account(
    account_id: UUID,
    payload: ResumeRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        return account_record(
            AccountRepository(session).resume(account_id, actor=actor, confirmed=payload.confirmed)
        )
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.put("/{account_id}/credentials")
def store_credentials(
    account_id: UUID,
    payload: CredentialRequest,
    request: Request,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    if session.get(AccountRecord, str(account_id)) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="계좌를 찾을 수 없습니다.")
    master_key = request.app.state.settings.credential_master_key
    if not master_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="자격증명 마스터 키가 설정되지 않았습니다.",
        )
    cipher = CredentialCipher(master_key)
    encrypted = cipher.encrypt(str(account_id), payload.model_dump())
    masked = f"***{payload.account_number[-4:]}"
    record = session.get(BrokerCredentialRecord, str(account_id))
    if record is None:
        record = BrokerCredentialRecord(account_id=str(account_id))
    record.key_version = encrypted.key_version
    record.nonce = encrypted.nonce
    record.ciphertext = encrypted.ciphertext
    record.masked_account = masked
    session.add(record)
    session.add(
        AuditEventRecord(
            actor=actor,
            action="ACCOUNT_CREDENTIALS_UPDATED",
            resource_type="ACCOUNT",
            resource_id=str(account_id),
            details={"masked_account": masked, "key_version": encrypted.key_version},
        )
    )
    session.commit()
    return {
        "account_id": str(account_id),
        "configured": True,
        "masked_account": masked,
        "key_version": encrypted.key_version,
    }
