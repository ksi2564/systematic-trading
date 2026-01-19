package my.side.trading.core.domain.execution.order;

/**
 * 주문 취소 포트
 */
public interface OrderCanceller {
    CancelResult cancel(String brokerOrderId, String symbol);
}
