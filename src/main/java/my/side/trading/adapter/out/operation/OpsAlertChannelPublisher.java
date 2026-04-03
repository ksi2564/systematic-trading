package my.side.trading.adapter.out.operation;

import my.side.trading.core.domain.operation.OpsAlert;

public interface OpsAlertChannelPublisher {
    void publish(OpsAlert alert);
}
