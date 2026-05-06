package my.side.trading.core.domain.execution.order;

public record BrokerOrderResult(
        ResultType type,
        String brokerOrderId,
        String message
) {
    public enum ResultType {
        ACCEPTED,
        REJECTED,
        CONFIRMATION_REQUIRED
    }

    public BrokerOrderResult {
        if (type == null) {
            throw new IllegalArgumentException("result type은 필수");
        }
    }

    public static BrokerOrderResult success(String brokerOrderId, String message) {
        return new BrokerOrderResult(ResultType.ACCEPTED, brokerOrderId, message);
    }

    public static BrokerOrderResult failure(String brokerOrderId, String message) {
        return new BrokerOrderResult(ResultType.REJECTED, brokerOrderId, message);
    }

    public static BrokerOrderResult confirmationRequired(String brokerOrderId, String message) {
        return new BrokerOrderResult(ResultType.CONFIRMATION_REQUIRED, brokerOrderId, message);
    }

    public boolean success() {
        return type == ResultType.ACCEPTED;
    }

    public boolean confirmationRequired() {
        return type == ResultType.CONFIRMATION_REQUIRED;
    }
}
