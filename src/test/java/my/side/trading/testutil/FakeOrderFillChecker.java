package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.FillResult;
import my.side.trading.core.domain.execution.order.OrderFillChecker;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 테스트용 OrderFillChecker 대역
 */
public class FakeOrderFillChecker implements OrderFillChecker {

    private final Map<String, FillResult> results = new HashMap<>();

    public void setFillResult(String brokerOrderId, FillResult result) {
        results.put(brokerOrderId, result);
    }

    public void setFullyFilled(String brokerOrderId, long qty, BigDecimal amount) {
        results.put(brokerOrderId, FillResult.full(qty, amount));
    }

    public void setPartialFilled(String brokerOrderId, long filledQty, long unfilledQty, BigDecimal amount) {
        results.put(brokerOrderId, FillResult.partial(filledQty, unfilledQty, amount));
    }

    @Override
    public FillResult checkFill(String brokerOrderId, String symbol) {
        return results.getOrDefault(brokerOrderId, FillResult.notFound());
    }
}
