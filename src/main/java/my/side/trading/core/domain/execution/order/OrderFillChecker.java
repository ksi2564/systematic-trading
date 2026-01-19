package my.side.trading.core.domain.execution.order;

/**
 * 체결 상태 조회 포트
 */
public interface OrderFillChecker {
    FillResult checkFill(String brokerOrderId, String symbol);
}
