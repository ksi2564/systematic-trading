package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.OrderBroker;

import java.util.HashMap;
import java.util.Map;

public class FakeOrderBroker implements OrderBroker {

    private final Map<Long, BrokerOrderResult> byOrderId = new HashMap<>();

    public void willReturn(Long orderId, BrokerOrderResult result) {
        byOrderId.put(orderId, result);
    }

    @Override
    public BrokerOrderResult place(ExecutionOrder order) {
        BrokerOrderResult result = byOrderId.get(order.getId());
        if (result == null) throw new IllegalStateException("no stub for orderId=" + order.getId());
        return result;
    }
}
