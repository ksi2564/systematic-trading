package my.side.trading.core.application.execution;

public class ExecutionRiskLimitExceededException extends ExecutionBlockedException {

    private final ExecutionRiskViolation violation;

    public ExecutionRiskLimitExceededException(ExecutionRiskViolation violation) {
        super(ExecutionBlockReason.RISK_LIMIT_BREACH, violation.summary());
        this.violation = violation;
    }

    public ExecutionRiskViolation getViolation() {
        return violation;
    }
}
