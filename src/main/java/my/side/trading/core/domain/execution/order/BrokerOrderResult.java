package my.side.trading.core.domain.execution.order;

public record BrokerOrderResult(
        boolean success,
        String brokerOrderId,
        String message
) {
    public static BrokerOrderResult success(String brokerOrderId, String message) {
        return new BrokerOrderResult(true, brokerOrderId, message);
    }
    public static BrokerOrderResult failure(String brokerOrderId, String message) {
        return new BrokerOrderResult(false, brokerOrderId, message);
    }
}
