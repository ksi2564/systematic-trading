package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.OrderBroker;

import java.util.*;

public class FakeOrderBroker implements OrderBroker {

    private final Map<Long, BrokerOrderResult> byOrderId = new HashMap<>();
    private final Map<Long, List<BrokerOrderResult>> sequenceByOrderId = new HashMap<>();
    private final Map<Long, Integer> callCountByOrderId = new HashMap<>();

    public void willReturn(Long orderId, BrokerOrderResult result) {
        byOrderId.put(orderId, result);
    }

    public void willReturnSequence(Long orderId, BrokerOrderResult... results) {
        sequenceByOrderId.put(orderId, new ArrayList<>(Arrays.asList(results)));
        callCountByOrderId.put(orderId, 0);
    }

    @Override
    public BrokerOrderResult place(ExecutionOrder order) {
        Long orderId = order.getId();

        // 순차 응답이 설정된 경우
        if (sequenceByOrderId.containsKey(orderId)) {
            List<BrokerOrderResult> sequence = sequenceByOrderId.get(orderId);
            int callCount = callCountByOrderId.getOrDefault(orderId, 0);
            callCountByOrderId.put(orderId, callCount + 1);

            if (callCount < sequence.size()) {
                return sequence.get(callCount);
            }
            // 시퀀스 끝나면 마지막 결과 반환
            return sequence.get(sequence.size() - 1);
        }

        // 단일 응답
        BrokerOrderResult result = byOrderId.get(orderId);
        if (result == null) {
            throw new IllegalStateException("no stub for orderId=" + orderId);
        }
        return result;
    }
}
