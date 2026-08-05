from __future__ import annotations

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.orm import Session

from wallant.api import accounts, market_data, operations, strategies
from wallant.api.dependencies import get_session

router = APIRouter(prefix="/qa", tags=["qa"])


def _qa_catalog(entries: list[dict]) -> list[dict]:
    """Project only fields rendered by the deployed read-only QA screen."""
    allowed = (
        "symbol",
        "market",
        "resolution",
        "provider",
        "official",
        "start_date",
        "end_date",
        "row_count",
    )
    return [
        {"id": f"qa-catalog-{index}", **{key: entry[key] for key in allowed}}
        for index, entry in enumerate(entries, start=1)
    ]


def _qa_audit(entries: list[dict]) -> list[dict]:
    """Keep table shape while removing actors, resource IDs, and arbitrary details."""
    return [
        {
            "id": f"qa-audit-{index}",
            "actor": "redacted",
            "action": entry["action"],
            "resource_type": entry["resource_type"],
            "resource_id": "redacted" if entry["resource_id"] is not None else None,
            "details": {},
            "created_at": entry["created_at"],
        }
        for index, entry in enumerate(entries, start=1)
    ]


@router.get("/deployed-read-only-snapshot")
def deployed_read_only_snapshot(
    request: Request,
    response: Response,
    session: Session = Depends(get_session),
) -> dict:
    """Return one build-pinned, non-mutating payload for authenticated UI QA.

    The path intentionally does not exist in pre-harness releases. A rollback between
    the health gate and this request therefore returns 404 instead of touching an old
    status endpoint whose GET-on-miss behavior was not pure.
    """
    response.headers["Cache-Control"] = "no-store"
    status = operations.status(request=request, response=response, session=session)
    return {
        "schema_version": "1.0",
        "status": "UP",
        "build_sha": status["build_sha"],
        "execution_enabled": status["execution_enabled"],
        "broker_adapter": status["broker_adapter"],
        "responses": {
            "operations-status": status,
            "strategies": strategies.list_strategies(session=session),
            "accounts": accounts.list_accounts(session=session),
            "market-data-catalog": _qa_catalog(market_data.catalog(session=session)),
            "operations-audit-limit-30": _qa_audit(
                operations.audit_events(limit=30, session=session)
            ),
        },
    }
