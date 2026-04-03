package my.side.trading.adapter.out.operation;

import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OpsAlertPublisherPipelineTest {

    @Test
    void composite가_모든_채널로_alert를_전달한다() {
        RecordingChannelPublisher first = new RecordingChannelPublisher();
        RecordingChannelPublisher second = new RecordingChannelPublisher();

        OpsAlertPublisher composite = new CompositeOpsAlertPublisher(List.of(first, second));
        OpsAlert alert = sampleAlert();

        composite.publish(alert);

        assertThat(first.alerts).containsExactly(alert);
        assertThat(second.alerts).containsExactly(alert);
    }

    @Test
    void dedupe가_동일_key_alert를_한번만_전달한다() {
        RecordingChannelPublisher first = new RecordingChannelPublisher();
        RecordingChannelPublisher second = new RecordingChannelPublisher();

        OpsAlertPublisher composite = new CompositeOpsAlertPublisher(List.of(first, second));
        OperatingModeService operatingModeService = mock(OperatingModeService.class);
        OpsAlertPublisher deduplicating = new DeduplicatingOpsAlertPublisher(
                operationProps(true),
                operatingModeService,
                composite);
        OpsAlert alert = sampleAlert();

        deduplicating.publish(alert);
        deduplicating.publish(alert);

        assertThat(first.alerts).containsExactly(alert);
        assertThat(second.alerts).containsExactly(alert);
        verify(operatingModeService, times(2)).applySystemAlert(alert);
    }

    @Test
    void alertsDisabled여도_autoDemotionHook은_실행된다() {
        RecordingChannelPublisher first = new RecordingChannelPublisher();
        OpsAlertPublisher composite = new CompositeOpsAlertPublisher(List.of(first));
        OperatingModeService operatingModeService = mock(OperatingModeService.class);
        OpsAlert alert = sampleAlert();

        OpsAlertPublisher deduplicating = new DeduplicatingOpsAlertPublisher(
                operationProps(false),
                operatingModeService,
                composite);

        deduplicating.publish(alert);

        assertThat(first.alerts).isEmpty();
        verify(operatingModeService).applySystemAlert(alert);
    }

    private TradingOperationProps operationProps(boolean alertsEnabled) {
        return new TradingOperationProps(
                OperatingMode.AUTO_LIVE,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                new TradingOperationProps.RiskLimitProps(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new TradingOperationProps.AlertsProps(
                        alertsEnabled,
                        30,
                        new TradingOperationProps.DiscordProps(false, "", OpsAlertSeverity.ERROR)));
    }

    private OpsAlert sampleAlert() {
        return new OpsAlert(
                OpsAlertType.EOD_FAILURE,
                OpsAlertSeverity.ERROR,
                "sample-key",
                "sample message",
                Map.of("marketDate", "2026-04-02"));
    }

    private static class RecordingChannelPublisher implements OpsAlertChannelPublisher {
        private final List<OpsAlert> alerts = new ArrayList<>();

        @Override
        public void publish(OpsAlert alert) {
            alerts.add(alert);
        }
    }
}
