package my.side.trading.adapter.out.operation;

import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Slf4j
@Component
public class LoggingOpsAlertPublisher implements OpsAlertChannelPublisher {

    @Override
    public void publish(OpsAlert alert) {
        try {
            var sanitizedDetails = SensitiveDataSanitizer.sanitizeMap(alert.details());
            String details = alert.details().isEmpty()
                    ? "-"
                    : sanitizedDetails.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            String dedupeKey = SensitiveDataSanitizer.sanitize(alert.dedupeKey());
            String message = SensitiveDataSanitizer.sanitize(alert.message());

            if (alert.severity() == OpsAlertSeverity.ERROR) {
                log.error("[OPS_ALERT] type={} key={} message={} details={}",
                        alert.type(),
                        dedupeKey,
                        message,
                        details);
                return;
            }

            log.warn("[OPS_ALERT] type={} key={} message={} details={}",
                    alert.type(),
                    dedupeKey,
                    message,
                    details);
        } catch (Exception e) {
            log.warn("ops alert 로깅에 실패했습니다: type={}, reason={}",
                    alert.type(),
                    SensitiveDataSanitizer.sanitizeThrowable(e));
        }
    }
}
