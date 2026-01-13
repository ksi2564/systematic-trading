package my.side.trading.core.domain.execution.order;

public record OrderInquiryResult(
        boolean found,
        String brokerOrderId,
        String message
) {
    public static OrderInquiryResult found(String brokerOrderId, String message) {
        return new OrderInquiryResult(true, brokerOrderId, message);
    }

    public static OrderInquiryResult notFound(String message) {
        return new OrderInquiryResult(false, null, message);
    }
}
