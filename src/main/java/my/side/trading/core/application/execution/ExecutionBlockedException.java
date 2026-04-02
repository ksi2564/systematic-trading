package my.side.trading.core.application.execution;

public class ExecutionBlockedException extends RuntimeException {
    private final ExecutionBlockReason reason;

    public ExecutionBlockedException(ExecutionBlockReason reason) {
        super(reason.code());
        this.reason = reason;
    }

    public ExecutionBlockReason getReason() {
        return reason;
    }
}
