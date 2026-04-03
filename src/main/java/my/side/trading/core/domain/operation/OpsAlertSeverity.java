package my.side.trading.core.domain.operation;

public enum OpsAlertSeverity {
    WARN,
    ERROR;

    public boolean isAtLeast(OpsAlertSeverity threshold) {
        if (threshold == null) {
            return true;
        }
        return this.ordinal() >= threshold.ordinal();
    }
}
