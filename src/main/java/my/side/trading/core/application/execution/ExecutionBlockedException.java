package my.side.trading.core.application.execution;

public class ExecutionBlockedException extends RuntimeException {
    private final ExecutionBlockReason reason;

    public ExecutionBlockedException(ExecutionBlockReason reason) {
        this(reason, reason.code());
    }

    public ExecutionBlockedException(ExecutionBlockReason reason, String message) {
        super(message == null || message.isBlank() ? reason.code() : message);
        this.reason = reason;
    }

    public ExecutionBlockReason getReason() {
        return reason;
    }
}
