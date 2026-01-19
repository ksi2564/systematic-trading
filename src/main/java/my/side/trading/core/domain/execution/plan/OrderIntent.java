package my.side.trading.core.domain.execution.plan;

import my.side.trading.core.domain.execution.order.ExecutionOrderSide;

/**
 * 주문 의도 (금액 없이 종목과 방향만 표현)
 * 실제 금액/수량은 주문 시점에 실시간 가격으로 계산
 */
public record OrderIntent(
                String symbol,
                ExecutionOrderSide side,
                String reason) {
}
