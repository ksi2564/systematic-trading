package my.side.trading.core.application.execution;

public class ExecutionBlockedException extends RuntimeException {
    public ExecutionBlockedException(String reason) {
        super(reason);
    }
}
