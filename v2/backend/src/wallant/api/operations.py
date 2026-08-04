from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from wallant.api.dependencies import get_actor, get_session
from wallant.api.schemas import PauseRequest, ResumeRequest
from wallant.api.serializers import utc_iso
from wallant.persistence.models import (
    AccountRecord,
    AuditEventRecord,
    OrderIntentRecord,
    StrategyRecord,
)
from wallant.persistence.repositories import GlobalControlRepository

router = APIRouter(prefix="/operations", tags=["operations"])


@router.get("/status")
def status(
    request: Request,
    response: Response,
    session: Session = Depends(get_session),
) -> dict:
    response.headers["Cache-Control"] = "no-store"
    control = GlobalControlRepository(session).get()
    counts = {
        "strategies": session.scalar(select(func.count()).select_from(StrategyRecord)) or 0,
        "accounts": session.scalar(select(func.count()).select_from(AccountRecord)) or 0,
        "paused_accounts": session.scalar(
            select(func.count()).select_from(AccountRecord).where(AccountRecord.status == "PAUSED")
        )
        or 0,
        "order_intents": session.scalar(select(func.count()).select_from(OrderIntentRecord)) or 0,
    }
    return {
        "service": "UP",
        "environment": request.app.state.settings.environment,
        "build_sha": request.app.state.settings.build_sha,
        "execution_enabled": False,
        "broker_adapter": "disabled",
        "global_emergency_paused": control.emergency_paused,
        "global_reason": control.reason,
        "counts": counts,
    }


@router.post("/pause")
def pause_global(
    payload: PauseRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    control = GlobalControlRepository(session).pause(payload.reason, actor=actor)
    return {"emergency_paused": control.emergency_paused, "reason": control.reason}


@router.post("/resume")
def resume_global(
    payload: ResumeRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    control = GlobalControlRepository(session).resume(actor=actor, confirmed=payload.confirmed)
    return {"emergency_paused": control.emergency_paused, "reason": control.reason}


@router.get("/audit")
def audit_events(
    limit: int = Query(default=100, ge=1, le=500),
    session: Session = Depends(get_session),
) -> list[dict]:
    rows = session.scalars(
        select(AuditEventRecord).order_by(AuditEventRecord.created_at.desc()).limit(limit)
    )
    return [
        {
            "id": row.id,
            "actor": row.actor,
            "action": row.action,
            "resource_type": row.resource_type,
            "resource_id": row.resource_id,
            "details": row.details,
            "created_at": utc_iso(row.created_at),
        }
        for row in rows
    ]
