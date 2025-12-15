package my.side.trading.core.domain.execution.order;

import java.math.BigDecimal;

public record ExecutionOrder(
        Long id,
        String symbol,
        OrderSide side,
        BigDecimal quantity,            // 주식 수
        BigDecimal refPrice,            // 계획 시점 기준 가격(RealtimePrice)
        ExecutionOrderStatus status,
        BigDecimal filledQty,           // 누적 체결수량
        String brokerOrderId,           // KIS 주문번호(있는지 확인)
        String lastError                // 실패 메세지
) {
    public ExecutionOrder {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
