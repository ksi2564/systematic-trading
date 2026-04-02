package my.side.trading.core.infrastructure.config;

import my.side.trading.core.domain.operation.OperatingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "trading.operation")
public record TradingOperationProps(
        OperatingMode mode,
        AutoLiveGateProps autoLiveGate,
        KpiProps kpi,
        RiskLimitProps riskLimits
) {
    public TradingOperationProps {
        mode = mode == null ? OperatingMode.PAPER : mode;
        autoLiveGate = autoLiveGate == null ? new AutoLiveGateProps(5, true, true, true) : autoLiveGate;
        kpi = kpi == null ? new KpiProps(true, 0, 0, new BigDecimal("5.0")) : kpi;
        riskLimits = riskLimits == null ? new RiskLimitProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) : riskLimits;
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

    public record RiskLimitProps(
            BigDecimal maxOrderNotionalUsd,
            BigDecimal maxDailyTurnoverPct,
            BigDecimal maxRetryExposureUsd,
            BigDecimal maxSlippagePct
    ) {
        public RiskLimitProps {
            maxOrderNotionalUsd = normalize(maxOrderNotionalUsd);
            maxDailyTurnoverPct = normalize(maxDailyTurnoverPct);
            maxRetryExposureUsd = normalize(maxRetryExposureUsd);
            maxSlippagePct = normalize(maxSlippagePct);
        }

        private static BigDecimal normalize(BigDecimal value) {
            if (value == null || value.signum() < 0) {
                return BigDecimal.ZERO;
            }
            return value;
        }
    }
}
