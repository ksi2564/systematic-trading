package my.side.trading.core.infrastructure.config;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "trading.operation")
public record TradingOperationProps(
        OperatingMode mode,
        AutoLiveGateProps autoLiveGate,
        KpiProps kpi,
        RiskLimitProps riskLimits,
        AlertsProps alerts
) {
    public TradingOperationProps {
        mode = mode == null ? OperatingMode.PAPER : mode;
        autoLiveGate = autoLiveGate == null ? new AutoLiveGateProps(5, true, true, true) : autoLiveGate;
        kpi = kpi == null ? new KpiProps(true, 0, 0, new BigDecimal("5.0")) : kpi;
        riskLimits = riskLimits == null ? new RiskLimitProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) : riskLimits;
        alerts = alerts == null ? new AlertsProps(false, 30, null) : alerts;
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

    public record AlertsProps(
            boolean enabled,
            long dedupeTtlMinutes,
            DiscordProps discord
    ) {
        public AlertsProps {
            dedupeTtlMinutes = dedupeTtlMinutes <= 0 ? 30 : dedupeTtlMinutes;
            discord = discord == null ? new DiscordProps(false, "", OpsAlertSeverity.ERROR, null, null) : discord;
        }
    }

    public record DiscordProps(
            boolean enabled,
            String webhookUrl,
            OpsAlertSeverity minSeverity,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

        public DiscordProps {
            webhookUrl = webhookUrl == null ? "" : webhookUrl;
            minSeverity = minSeverity == null ? OpsAlertSeverity.ERROR : minSeverity;
            connectTimeout = normalizeDuration(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
            requestTimeout = normalizeDuration(requestTimeout, DEFAULT_REQUEST_TIMEOUT);
        }

        private static Duration normalizeDuration(Duration value, Duration defaultValue) {
            if (value == null || value.isZero() || value.isNegative()) {
                return defaultValue;
            }
            return value;
        }
    }
}
