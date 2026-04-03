package my.side.trading.adapter.out.operation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Primary
@Component
public class DeduplicatingOpsAlertPublisher implements OpsAlertPublisher {

    private final TradingOperationProps operationProps;
    private final OperatingModeService operatingModeService;
    private final OpsAlertPublisher delegate;
    private final Cache<String, Boolean> dedupeCache;

    public DeduplicatingOpsAlertPublisher(
            TradingOperationProps operationProps,
            OperatingModeService operatingModeService,
            @Qualifier("compositeOpsAlertPublisher") OpsAlertPublisher delegate
    ) {
        this.operationProps = operationProps;
        this.operatingModeService = operatingModeService;
        this.delegate = delegate;
        this.dedupeCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(operationProps.alerts().dedupeTtlMinutes()))
                .maximumSize(10_000)
                .build();
    }

    @Override
    public void publish(OpsAlert alert) {
        try {
            operatingModeService.applySystemAlert(alert);
            if (!operationProps.alerts().enabled()) {
                return;
            }
            if (dedupeCache.asMap().putIfAbsent(alert.dedupeKey(), Boolean.TRUE) != null) {
                return;
            }
            delegate.publish(alert);
        } catch (Exception ignored) {
            // Alert pipeline must never break the main flow.
        }
    }
}
