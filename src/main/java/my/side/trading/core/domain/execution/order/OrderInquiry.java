package my.side.trading.core.domain.execution.order;

public interface OrderInquiry {
    OrderInquiryResult confirm(ExecutionOrder order);
}
