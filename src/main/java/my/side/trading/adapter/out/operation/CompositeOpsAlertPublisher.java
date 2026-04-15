package my.side.trading.adapter.out.operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component("compositeOpsAlertPublisher")
@RequiredArgsConstructor
public class CompositeOpsAlertPublisher implements OpsAlertPublisher {

    private final List<OpsAlertChannelPublisher> channelPublishers;

    @Override
    public void publish(OpsAlert alert) {
        for (OpsAlertChannelPublisher channelPublisher : channelPublishers) {
            try {
                channelPublisher.publish(alert);
            } catch (Exception e) {
                log.warn("ops alert 채널 전송에 실패했습니다: type={}, publisher={}",
                        alert.type(),
                        channelPublisher.getClass().getSimpleName(),
                        e);
            }
        }
    }
}
