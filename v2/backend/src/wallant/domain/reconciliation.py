from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class SubmissionOutcome(StrEnum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    TIMEOUT = "TIMEOUT"
    NETWORK_ERROR = "NETWORK_ERROR"


class ReconciliationState(StrEnum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"


@dataclass(frozen=True, slots=True)
class ReconciliationDecision:
    state: ReconciliationState
    resend_allowed: bool
    pause_account: bool
    reason: str


class OrderReconciliationPolicy:
    """주문 접수 여부가 불명확할 때 중복 전송을 금지한다."""

    def after_submission(self, outcome: SubmissionOutcome) -> ReconciliationDecision:
        if outcome == SubmissionOutcome.ACCEPTED:
            return ReconciliationDecision(
                state=ReconciliationState.ACCEPTED,
                resend_allowed=False,
                pause_account=False,
                reason="브로커 주문 번호가 확인되었습니다.",
            )
        if outcome == SubmissionOutcome.REJECTED:
            return ReconciliationDecision(
                state=ReconciliationState.REJECTED,
                resend_allowed=False,
                pause_account=False,
                reason="브로커가 주문을 명시적으로 거절했습니다.",
            )
        return ReconciliationDecision(
            state=ReconciliationState.CONFIRMATION_REQUIRED,
            resend_allowed=False,
            pause_account=True,
            reason="주문 접수 여부가 불명확하여 계좌를 정지하고 브로커 조회가 필요합니다.",
        )
