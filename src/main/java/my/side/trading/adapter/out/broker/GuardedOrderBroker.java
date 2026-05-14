package my.side.trading.adapter.out.broker;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.execution.ExecutionBlockedException;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.domain.execution.order.BrokerOrderResult;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.OrderBroker;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
public class GuardedOrderBroker implements OrderBroker {

    private final ExecutionGuard guard;
    private final @Qualifier("kisOrderBroker") OrderBroker delegate; // 실제 KisOrderBroker를 주입한다.

    @Override
    public BrokerOrderResult place(ExecutionOrder order) {
        try {
            guard.requireOrderPlacementAllowed();
        } catch (ExecutionBlockedException e) {
            // "실패 주문"으로 오염시키지 않도록 BLOCKED reason을 명확히 남긴다.
            // "BLOCKED:" prefix로 구분하므로 message 수정 시 주의한다.
            return BrokerOrderResult.failure(null, "BLOCKED: " + SensitiveDataSanitizer.sanitize(e.getMessage()));
        }
        return delegate.place(order);
    }
}
