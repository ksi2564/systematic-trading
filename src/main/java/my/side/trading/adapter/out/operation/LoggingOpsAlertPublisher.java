package my.side.trading.adapter.out.operation;

import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Slf4j
@Component
public class LoggingOpsAlertPublisher implements OpsAlertChannelPublisher {

    @Override
    public void publish(OpsAlert alert) {
        try {
            String details = alert.details().isEmpty()
                    ? "-"
                    : alert.details().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));

            if (alert.severity() == OpsAlertSeverity.ERROR) {
                log.error("[OPS_ALERT] type={} key={} message={} details={}",
                        alert.type(),
                        alert.dedupeKey(),
                        alert.message(),
                        details);
                return;
            }

            log.warn("[OPS_ALERT] type={} key={} message={} details={}",
                    alert.type(),
                    alert.dedupeKey(),
                    alert.message(),
                    details);
        } catch (Exception e) {
            log.warn("ops alert logging failed: type={}", alert.type(), e);
        }
    }
}
