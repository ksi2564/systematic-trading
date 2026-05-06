package my.side.trading.core.domain.execution.order;

public record OrderInquiryResult(
        Status status,
        String brokerOrderId,
        String message
) {
    public enum Status {
        FOUND,
        NOT_FOUND,
        INQUIRY_FAILED
    }

    public OrderInquiryResult {
        if (status == null) {
            throw new IllegalArgumentException("inquiry status는 필수");
        }
    }

    public static OrderInquiryResult found(String brokerOrderId, String message) {
        return new OrderInquiryResult(Status.FOUND, brokerOrderId, message);
    }

    public static OrderInquiryResult notFound(String message) {
        return new OrderInquiryResult(Status.NOT_FOUND, null, message);
    }

    public static OrderInquiryResult failed(String message) {
        return new OrderInquiryResult(Status.INQUIRY_FAILED, null, message);
    }

    public boolean found() {
        return status == Status.FOUND;
    }
}
