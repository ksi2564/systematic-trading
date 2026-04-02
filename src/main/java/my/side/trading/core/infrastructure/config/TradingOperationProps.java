package my.side.trading.core.infrastructure.config;

import my.side.trading.core.domain.operation.OperatingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.operation")
public record TradingOperationProps(
        OperatingMode mode,
        AutoLiveGateProps autoLiveGate
) {
    public TradingOperationProps {
        mode = mode == null ? OperatingMode.PAPER : mode;
        autoLiveGate = autoLiveGate == null ? new AutoLiveGateProps(5, true, true, true) : autoLiveGate;
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
}
