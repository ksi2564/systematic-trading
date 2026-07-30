from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from wallant.api.dependencies import get_actor, get_session
from wallant.api.schemas import CreateStrategyRequest, LifecycleTransitionRequest
from wallant.api.serializers import json_value, strategy_record
from wallant.domain.defaults import qqqm_drawdown_definition
from wallant.domain.strategy import StrategyLifecycle
from wallant.persistence.repositories import (
    DuplicateStrategyVersionError,
    InvalidLifecycleTransitionError,
    StrategyRepository,
)

router = APIRouter(prefix="/strategies", tags=["strategies"])


@router.get("")
def list_strategies(session: Session = Depends(get_session)) -> list[dict]:
    return [strategy_record(record) for record in StrategyRepository(session).list()]


@router.post("", status_code=status.HTTP_201_CREATED)
def create_strategy(
    request: CreateStrategyRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        return json_value(StrategyRepository(session).create(request.definition, actor=actor))
    except DuplicateStrategyVersionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.post("/baseline/qqqm", status_code=status.HTTP_201_CREATED)
def create_qqqm_baseline(
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        return json_value(StrategyRepository(session).create(qqqm_drawdown_definition(), actor=actor))
    except DuplicateStrategyVersionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.post("/{strategy_id}/versions", status_code=status.HTTP_201_CREATED)
def add_strategy_version(
    strategy_id: UUID,
    request: CreateStrategyRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    try:
        return json_value(
            StrategyRepository(session).add_version(
                strategy_id,
                request.definition,
                actor=actor,
            )
        )
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except DuplicateStrategyVersionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.get("/versions/{version_id}")
def get_strategy_version(
    version_id: UUID,
    session: Session = Depends(get_session),
) -> dict:
    try:
        return json_value(StrategyRepository(session).get_version(version_id))
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.post("/versions/{version_id}/transition")
def transition_strategy(
    version_id: UUID,
    request: LifecycleTransitionRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    if request.target == StrategyLifecycle.LIVE_APPROVED and not request.confirmed:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="실전 승인에는 명시적인 확인이 필요합니다.",
        )
    try:
        version = StrategyRepository(session).transition(
            version_id,
            request.target,
            actor=actor,
            evidence=request.evidence,
        )
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except InvalidLifecycleTransitionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    return json_value(version)
