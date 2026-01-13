package my.side.trading.core.domain.execution.order;

public interface OrderBroker {
    BrokerOrderResult place(ExecutionOrder order);
}
