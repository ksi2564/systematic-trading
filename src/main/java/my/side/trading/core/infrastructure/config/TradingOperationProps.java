package my.side.trading.core.infrastructure.config;

import my.side.trading.core.domain.operation.OperatingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.operation")
public record TradingOperationProps(
        OperatingMode mode,
        AutoLiveGateProps autoLiveGate,
        KpiProps kpi
) {
    public TradingOperationProps {
        mode = mode == null ? OperatingMode.PAPER : mode;
        autoLiveGate = autoLiveGate == null ? new AutoLiveGateProps(5, true, true, true) : autoLiveGate;
        kpi = kpi == null ? new KpiProps(true, 0, 0, new BigDecimal("5.0")) : kpi;
    }

    public record AutoLiveGateProps(
            int requiredConsecutiveEodSuccessDays,
            boolean requireZeroPendingOrders,
            boolean requireZeroDuplicateSignalJobs,
            boolean requireManualApprovalRecord
    ) {
        public AutoLiveGateProps {
            requiredConsecutiveEodSuccessDays = requiredConsecutiveEodSuccessDays <= 0
                    ? 5
                    : requiredConsecutiveEodSuccessDays;
        }
    }

    public record KpiProps(
            boolean requireLatestEodSuccess,
            int maxDuplicateSignalJobs,
            int maxUnresolvedOrders,
            BigDecimal maxOrderFailureRatePct
    ) {
        public KpiProps {
            maxDuplicateSignalJobs = Math.max(0, maxDuplicateSignalJobs);
            maxUnresolvedOrders = Math.max(0, maxUnresolvedOrders);
            maxOrderFailureRatePct = maxOrderFailureRatePct == null
                    ? new BigDecimal("5.0")
                    : maxOrderFailureRatePct;
        }
    }
}
