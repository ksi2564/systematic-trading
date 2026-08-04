from __future__ import annotations

import json
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from hashlib import sha256
from threading import Lock, RLock
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from wallant.api.dependencies import get_actor, get_session
from wallant.api.schemas import (
    BacktestRequest,
    CompletePaperSessionRequest,
    CreatePaperSessionRequest,
    EvaluateRequest,
    PaperStepRequest,
    RollingRequest,
)
from wallant.api.serializers import json_value
from wallant.domain.evaluator import EvaluatorRegistry, StrategyEvaluationError
from wallant.domain.execution import DailyRiskUsage, IntentStatus, RiskPolicy
from wallant.domain.money import ZERO
from wallant.domain.strategy import StrategyLifecycle
from wallant.persistence.models import (
    AccountRecord,
    AuditEventRecord,
    DailyRiskUsageRecord,
    OrderIntentRecord,
    StrategyRunRecord,
    StrategyStateRecord,
)
from wallant.persistence.repositories import StrategyRepository
from wallant.research.backtest import BacktestEngine
from wallant.research.paper import PaperSession, PaperTradingService
from wallant.research.rolling import RollingValidationService

router = APIRouter(prefix="/research", tags=["research"])
_paper_lock_guard = Lock()
_paper_session_locks: dict[str, RLock] = {}


@contextmanager
def _paper_session_lock(paper_session_id: UUID) -> Iterator[None]:
    key = str(paper_session_id)
    with _paper_lock_guard:
        lock = _paper_session_locks.setdefault(key, RLock())
    with lock:
        yield


def _risk_policy(account: AccountRecord) -> RiskPolicy:
    risk = account.risk_policy
    if risk is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="저장된 위험 한도가 없는 계좌는 모의투자에 사용할 수 없습니다.",
        )
    return RiskPolicy(
        max_order_notional=risk.max_order_notional,
        max_daily_notional=risk.max_daily_notional,
        max_daily_order_count=risk.max_daily_order_count,
        max_symbol_weight_pct=risk.max_symbol_weight_pct,
        max_daily_loss=risk.max_daily_loss,
    )


def _version(session: Session, version_id: UUID):
    try:
        return StrategyRepository(session).get_version(version_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.post("/evaluate")
def evaluate(
    request: EvaluateRequest,
    session: Session = Depends(get_session),
) -> dict:
    version = _version(session, request.version_id)
    try:
        return json_value(EvaluatorRegistry().evaluate(version, request.context))
    except StrategyEvaluationError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc


@router.post("/backtests")
def backtest(
    request: BacktestRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    version = _version(session, request.version_id)
    try:
        result = BacktestEngine().run(
            version,
            request.bars,
            initial_cash=request.initial_cash,
            corporate_actions=request.corporate_actions,
        )
    except (ValueError, StrategyEvaluationError) as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    session.add(
        StrategyRunRecord(
            id=str(result.id),
            strategy_version_id=str(version.id),
            run_type="BACKTEST",
            status="COMPLETED",
            start_date=result.metrics.start_date,
            end_date=result.metrics.end_date,
            summary=result.metrics.model_dump(mode="json"),
            evidence={"warnings": result.warnings},
            completed_at=datetime.now(UTC),
        )
    )
    session.add(
        AuditEventRecord(
            actor=actor,
            action="BACKTEST_COMPLETED",
            resource_type="STRATEGY_VERSION",
            resource_id=str(version.id),
            details={"run_id": str(result.id), "warnings": len(result.warnings)},
        )
    )
    session.commit()
    return result.model_dump(mode="json")


@router.post("/rolling")
def rolling(
    request: RollingRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    version = _version(session, request.version_id)
    try:
        result = RollingValidationService().run(
            version,
            request.bars,
            window_days=request.window_days,
            step_days=request.step_days,
            initial_cash=request.initial_cash,
            corporate_actions=request.corporate_actions,
        )
    except (ValueError, StrategyEvaluationError) as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc
    session.add(
        StrategyRunRecord(
            strategy_version_id=str(version.id),
            run_type="ROLLING",
            status="COMPLETED",
            start_date=result.windows[0].start_date,
            end_date=result.windows[-1].end_date,
            summary={
                "median_return_pct": str(result.median_return_pct),
                "worst_return_pct": str(result.worst_return_pct),
                "worst_drawdown_pct": str(result.worst_drawdown_pct),
                "window_count": len(result.windows),
            },
            evidence={"fixed_parameters": True, "warnings": result.warnings},
            completed_at=datetime.now(UTC),
        )
    )
    session.add(
        AuditEventRecord(
            actor=actor,
            action="ROLLING_VALIDATION_COMPLETED",
            resource_type="STRATEGY_VERSION",
            resource_id=str(version.id),
            details={"windows": len(result.windows), "fixed_parameters": True},
        )
    )
    session.commit()
    return result.model_dump(mode="json")


@router.post("/paper/sessions", status_code=status.HTTP_201_CREATED)
def create_paper_session(
    payload: CreatePaperSessionRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    version = _version(session, payload.version_id)
    if version.lifecycle != StrategyLifecycle.PAPER:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="PAPER 단계 전략 버전만 모의투자 세션을 시작할 수 있습니다.",
        )
    account = session.get(AccountRecord, str(payload.account_id))
    if account is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="모의투자에 연결할 계좌를 찾을 수 없습니다.",
        )
    if account.market != version.definition.market.value:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{account.market} 계좌에 {version.definition.market.value} 시장 전략의 "
                "모의투자 세션을 연결할 수 없습니다."
            ),
        )
    _risk_policy(account)
    run = StrategyRunRecord(
        id=str(uuid4()),
        strategy_version_id=str(version.id),
        run_type="PAPER",
        status="RUNNING",
        evidence={
            "account_id": str(payload.account_id),
            "previous_state": {},
            "previous_target_weights": {},
            "evaluated_days": 0,
            "signal_count": 0,
            "error_count": 0,
        },
    )
    session.add(run)
    session.add(
        AuditEventRecord(
            actor=actor,
            action="PAPER_SESSION_STARTED",
            resource_type="STRATEGY_VERSION",
            resource_id=str(version.id),
            details={
                "paper_session_id": run.id,
                "account_id": str(payload.account_id),
            },
        )
    )
    session.commit()
    return {"id": run.id, "status": run.status, "evidence": run.evidence}


@router.post("/paper/sessions/{paper_session_id}/steps")
def paper_step(
    paper_session_id: UUID,
    payload: PaperStepRequest,
    session: Session = Depends(get_session),
    _actor: str = Depends(get_actor),
) -> dict:
    with _paper_session_lock(paper_session_id):
        return _paper_step_locked(paper_session_id, payload, session)


def _paper_step_locked(
    paper_session_id: UUID,
    payload: PaperStepRequest,
    session: Session,
) -> dict:
    run = session.scalar(
        select(StrategyRunRecord)
        .where(StrategyRunRecord.id == str(paper_session_id))
        .with_for_update()
    )
    if run is None or run.run_type != "PAPER":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="모의투자 세션을 찾을 수 없습니다.")
    if run.status != "RUNNING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="실행 중인 모의투자 세션이 아닙니다."
        )
    version = _version(session, UUID(run.strategy_version_id))
    evidence = run.evidence or {}
    request_payload = json_value(payload)
    request_checksum = sha256(
        json.dumps(
            request_payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    last_attempt_date = evidence.get("last_attempt_date")
    if last_attempt_date == payload.context.as_of.isoformat():
        if (
            evidence.get("last_request_checksum") == request_checksum
            and evidence.get("last_result") is not None
        ):
            return evidence["last_result"]
        if (
            evidence.get("last_request_checksum") == request_checksum
            and evidence.get("last_error") is not None
        ):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=evidence["last_error"],
            )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="같은 평가일에 서로 다른 모의투자 입력을 적용할 수 없습니다.",
        )
    if last_attempt_date and payload.context.as_of.isoformat() < last_attempt_date:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="모의투자 입력은 평가일 순서대로 적용해야 합니다.",
        )
    paper_session = PaperSession(
        id=paper_session_id,
        account_id=UUID(evidence["account_id"]),
        previous_state=evidence.get("previous_state", {}),
        previous_target_weights=evidence.get("previous_target_weights", {}),
        evaluated_days=int(evidence.get("evaluated_days", 0)),
        signal_count=int(evidence.get("signal_count", 0)),
        error_count=int(evidence.get("error_count", 0)),
    )
    account = session.get(AccountRecord, evidence["account_id"])
    if account is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="연결된 계좌를 찾을 수 없습니다.")
    risk_policy = _risk_policy(account)
    usage_key = {
        "account_id": account.id,
        "usage_date": payload.context.as_of,
    }
    usage_record = session.scalar(
        select(DailyRiskUsageRecord)
        .where(
            DailyRiskUsageRecord.account_id == usage_key["account_id"],
            DailyRiskUsageRecord.usage_date == usage_key["usage_date"],
        )
        .with_for_update()
    )
    daily_usage = DailyRiskUsage(
        order_notional=usage_record.order_notional if usage_record else ZERO,
        order_count=usage_record.order_count if usage_record else 0,
        realized_loss=usage_record.realized_loss if usage_record else ZERO,
    )
    try:
        result = PaperTradingService().step(
            session=paper_session,
            version=version,
            context=payload.context,
            portfolio=payload.portfolio,
            quotes=payload.quotes,
            risk_policy=risk_policy,
            daily_usage=daily_usage,
        )
    except (ValueError, StrategyEvaluationError) as exc:
        run.evidence = {
            **evidence,
            "error_count": paper_session.error_count,
            "last_attempt_date": payload.context.as_of.isoformat(),
            "last_request_checksum": request_checksum,
            "last_error": str(exc),
            "last_result": None,
        }
        session.commit()
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from exc

    serialized_result = json_value(result)
    run.evidence = {
        "account_id": str(paper_session.account_id),
        "previous_state": json_value(paper_session.previous_state),
        "previous_target_weights": json_value(paper_session.previous_target_weights),
        **result.evidence,
        "last_attempt_date": payload.context.as_of.isoformat(),
        "last_request_checksum": request_checksum,
        "last_error": None,
        "last_result": serialized_result,
    }
    run.start_date = run.start_date or result.evaluation.as_of
    run.end_date = result.evaluation.as_of
    session.add(
        StrategyStateRecord(
            paper_session_id=run.id,
            account_id=account.id,
            strategy_version_id=str(version.id),
            as_of_date=result.evaluation.as_of,
            state=json_value(result.evaluation.state),
            target_weights=json_value(result.evaluation.target_weights),
            explanation=result.evaluation.explanation,
        )
    )
    for intent in result.order_plan.intents:
        session.add(
            OrderIntentRecord(
                idempotency_key=intent.idempotency_key,
                paper_session_id=run.id,
                account_id=account.id,
                strategy_version_id=str(version.id),
                signal_date=intent.signal_date,
                symbol=intent.symbol,
                side=intent.side.value,
                quantity=intent.quantity,
                reference_price=intent.reference_price,
                target_weight_pct=intent.target_weight_pct,
                status="PAPER_" + intent.status.value,
                reason=intent.reason,
                payload={"violations": list(intent.violations)},
            )
        )
    planned = [intent for intent in result.order_plan.intents if intent.status != IntentStatus.BLOCKED]
    if usage_record is None:
        usage_record = DailyRiskUsageRecord(
            account_id=account.id,
            usage_date=payload.context.as_of,
            order_notional=ZERO,
            order_count=0,
            realized_loss=ZERO,
        )
        session.add(usage_record)
    usage_record.order_notional += sum((intent.notional for intent in planned), ZERO)
    usage_record.order_count += len(planned)
    usage_record.realized_loss += result.realized_loss
    try:
        session.commit()
    except Exception:
        session.rollback()
        raise
    return serialized_result


@router.post("/paper/sessions/{paper_session_id}/complete")
def complete_paper_session(
    paper_session_id: UUID,
    payload: CompletePaperSessionRequest,
    session: Session = Depends(get_session),
    actor: str = Depends(get_actor),
) -> dict:
    if not payload.confirmed:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="모의투자 완료에는 명시적인 확인이 필요합니다.",
        )
    run = session.get(StrategyRunRecord, str(paper_session_id))
    if run is None or run.run_type != "PAPER":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="모의투자 세션을 찾을 수 없습니다.")
    if run.status == "COMPLETED":
        return json_value(
            {
                "id": run.id,
                "status": run.status,
                "summary": run.summary,
                "completed_at": run.completed_at,
            }
        )
    if run.status != "RUNNING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="실행 중인 모의투자 세션이 아닙니다.",
        )
    evidence = run.evidence or {}
    if int(evidence.get("evaluated_days", 0)) < 1:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="한 평가일 이상 실행한 뒤 모의투자를 완료할 수 있습니다.",
        )
    run.status = "COMPLETED"
    run.summary = {
        "evaluated_days": int(evidence.get("evaluated_days", 0)),
        "signal_count": int(evidence.get("signal_count", 0)),
        "error_count": int(evidence.get("error_count", 0)),
        "note": payload.note,
    }
    run.completed_at = datetime.now(UTC)
    session.add(
        AuditEventRecord(
            actor=actor,
            action="PAPER_SESSION_COMPLETED",
            resource_type="STRATEGY_VERSION",
            resource_id=run.strategy_version_id,
            details={"paper_session_id": run.id, **run.summary},
        )
    )
    session.commit()
    return json_value(
        {
            "id": run.id,
            "status": run.status,
            "summary": run.summary,
            "completed_at": run.completed_at,
        }
    )
