package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.CancelResult;
import my.side.trading.core.domain.execution.order.OrderCanceller;

import java.util.HashMap;
import java.util.Map;

/**
 * 테스트용 Fake OrderCanceller
 */
public class FakeOrderCanceller implements OrderCanceller {

    private final Map<String, CancelResult> results = new HashMap<>();
    private boolean defaultSuccess = true;

    public void setCancelResult(String brokerOrderId, CancelResult result) {
        results.put(brokerOrderId, result);
    }

    public void setDefaultSuccess(boolean success) {
        this.defaultSuccess = success;
    }

    @Override
    public CancelResult cancel(String brokerOrderId, String symbol) {
        if (results.containsKey(brokerOrderId)) {
            return results.get(brokerOrderId);
        }
        return defaultSuccess
                ? CancelResult.success("취소 완료")
                : CancelResult.failure("취소 실패");
    }
}
