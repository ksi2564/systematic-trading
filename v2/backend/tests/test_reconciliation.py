from wallant.domain.reconciliation import (
    OrderReconciliationPolicy,
    ReconciliationState,
    SubmissionOutcome,
)


def test_주문_타임아웃은_재전송하지_않고_계좌정지를_요구한다() -> None:
    decision = OrderReconciliationPolicy().after_submission(SubmissionOutcome.TIMEOUT)
    assert decision.state == ReconciliationState.CONFIRMATION_REQUIRED
    assert not decision.resend_allowed
    assert decision.pause_account


def test_명시적_거절은_접수불명으로_다루지_않는다() -> None:
    decision = OrderReconciliationPolicy().after_submission(SubmissionOutcome.REJECTED)
    assert decision.state == ReconciliationState.REJECTED
    assert not decision.pause_account
