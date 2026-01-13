package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.*;

import java.util.HashMap;
import java.util.Map;

public class FakeOrderInquiry implements OrderInquiry {

    private final Map<Long, OrderInquiryResult> byOrderId = new HashMap<>();

    public void willReturn(Long orderId, OrderInquiryResult result) {
        byOrderId.put(orderId, result);
    }

    @Override
    public OrderInquiryResult confirm(ExecutionOrder order) {
        OrderInquiryResult result = byOrderId.get(order.getId());
        if (result == null) return OrderInquiryResult.notFound("no stub");
        return result;
    }
}
