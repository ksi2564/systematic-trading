package my.side.trading.core.application.operation;

import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.core.infrastructure.config.TradingPricingProps;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import my.side.trading.core.infrastructure.config.TradingStrategyThresholdProps;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

@Component
public class PropertyBasedEffectiveParameterSnapshotProvider implements EffectiveParameterSnapshotProvider {

    private final TradingStrategyProps strategyProps;
    private final TradingStrategyThresholdProps strategyThresholdProps;
    private final TradingCircuitBreakerProps circuitBreakerProps;
    private final TradingPricingProps pricingProps;
    private final TradingOperationProps operationProps;

    public PropertyBasedEffectiveParameterSnapshotProvider(
            TradingStrategyProps strategyProps,
            TradingStrategyThresholdProps strategyThresholdProps,
            TradingCircuitBreakerProps circuitBreakerProps,
            TradingPricingProps pricingProps,
            TradingOperationProps operationProps
    ) {
        this.strategyProps = strategyProps;
        this.strategyThresholdProps = strategyThresholdProps;
        this.circuitBreakerProps = circuitBreakerProps;
        this.pricingProps = pricingProps;
        this.operationProps = operationProps;
    }

    @Override
    public Map<ParameterRegistryKey, String> snapshotAll() {
        EnumMap<ParameterRegistryKey, String> snapshots = new EnumMap<>(ParameterRegistryKey.class);
        snapshots.put(ParameterRegistryKey.DD_BUCKET, formatDrawdownBuckets());
        snapshots.put(ParameterRegistryKey.RECOVERY_RULE, formatRecoveryRule());
        snapshots.put(ParameterRegistryKey.REBALANCE_TOLERANCE, formatPercent(strategyProps.tolerancePct(), 1));
        snapshots.put(ParameterRegistryKey.VIX_THRESHOLD, formatDecimal(circuitBreakerProps.getVixThreshold(), 0));
        snapshots.put(ParameterRegistryKey.MA_200_GUARD,
                "QQQ < MA" + circuitBreakerProps.getMaPeriod() + " 시 공격 버킷 1단계 축소");
        snapshots.put(ParameterRegistryKey.ORDER_BUFFER_RETRY_POLICY, formatOrderRetryPolicy());
        snapshots.put(ParameterRegistryKey.MAX_ORDER_NOTIONAL_USD,
                formatOptionalLimit(operationProps.riskLimits().maxOrderNotionalUsd(), "USD"));
        snapshots.put(ParameterRegistryKey.MAX_DAILY_TURNOVER_PCT,
                formatOptionalLimit(operationProps.riskLimits().maxDailyTurnoverPct(), "%"));
        snapshots.put(ParameterRegistryKey.MAX_RETRY_EXPOSURE_USD,
                formatOptionalLimit(operationProps.riskLimits().maxRetryExposureUsd(), "USD"));
        snapshots.put(ParameterRegistryKey.MAX_SLIPPAGE_PCT,
                formatOptionalLimit(operationProps.riskLimits().maxSlippagePct(), "%"));
        return Map.copyOf(snapshots);
    }

    private String formatDrawdownBuckets() {
        var thresholds = strategyThresholdProps.drawdownThresholds();
        return "%s / %s / %s / %s%%".formatted(
                formatDecimal(thresholds.first(), 0),
                formatDecimal(thresholds.second(), 0),
                formatDecimal(thresholds.third(), 0),
                formatDecimal(thresholds.fourth(), 0));
    }

    private String formatRecoveryRule() {
        var thresholds = strategyThresholdProps.recoveryThresholds();
        return "최대 DD %s%% 이상 + 현재 DD %s%% 이하".formatted(
                formatDecimal(thresholds.activationMaxDrawdownPct(), 0),
                formatDecimal(thresholds.recoveryDrawdownPct(), 0));
    }

    private String formatOrderRetryPolicy() {
        return "초기 BUY %d tick / SELL %d tick, 재시도 BUY +%d tick / SELL +%d tick, 최대 %d회, 대기 %dms".formatted(
                pricingProps.initialBuyTicks(),
                pricingProps.initialSellTicks(),
                pricingProps.retryBuyTicks(),
                pricingProps.retrySellTicks(),
                pricingProps.maxAttempts(),
                pricingProps.retryWaitMs());
    }

    private String formatOptionalLimit(BigDecimal value, String unit) {
        if (value == null || value.signum() <= 0) {
            return "미정의 (0으로 비활성)";
        }
        return formatDecimal(value, 0) + " " + unit;
    }

    private String formatPercent(BigDecimal value, int minScale) {
        return formatDecimal(value, minScale) + "%";
    }

    private String formatDecimal(BigDecimal value, int minScale) {
        BigDecimal normalized = value.stripTrailingZeros();
        int scale = Math.max(normalized.scale(), minScale);
        return normalized.setScale(scale).toPlainString();
    }
}
