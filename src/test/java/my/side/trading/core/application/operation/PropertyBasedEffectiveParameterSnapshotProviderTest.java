package my.side.trading.core.application.operation;

import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.infrastructure.config.TradingCircuitBreakerProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.core.infrastructure.config.TradingPricingProps;
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import my.side.trading.core.infrastructure.config.TradingStrategyThresholdProps;
import my.side.trading.core.domain.operation.OperatingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyBasedEffectiveParameterSnapshotProviderTest {

    @Test
    void 각파라미터의현재적용값을사람이읽기좋은문자열로노출한다() {
        PropertyBasedEffectiveParameterSnapshotProvider provider = new PropertyBasedEffectiveParameterSnapshotProvider(
                new TradingStrategyProps(
                        new BigDecimal("5.0"),
                        List.of("QQQ", "QLD", "TQQQ"),
                        List.of("TQQQ", "QLD", "QQQ"),
                        List.of("QQQ", "QLD", "TQQQ")),
                new TradingStrategyThresholdProps(null, null),
                new TradingCircuitBreakerProps(true, true, new BigDecimal("35"), 200),
                new TradingPricingProps(new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000),
                new TradingOperationProps(
                        OperatingMode.PAPER,
                        new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                        new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                        new TradingOperationProps.RiskLimitProps(
                                BigDecimal.ZERO,
                                new BigDecimal("12.5"),
                                BigDecimal.ZERO,
                                new BigDecimal("0.8")),
                        new TradingOperationProps.AlertsProps(false, 30))
        );

        Map<ParameterRegistryKey, String> snapshots = provider.snapshotAll();

        assertThat(snapshots.get(ParameterRegistryKey.DD_BUCKET)).isEqualTo("15 / 25 / 35 / 45%");
        assertThat(snapshots.get(ParameterRegistryKey.RECOVERY_RULE)).isEqualTo("최대 DD 15% 이상 + 현재 DD 10% 이하");
        assertThat(snapshots.get(ParameterRegistryKey.REBALANCE_TOLERANCE)).isEqualTo("5.0%");
        assertThat(snapshots.get(ParameterRegistryKey.VIX_THRESHOLD)).isEqualTo("35");
        assertThat(snapshots.get(ParameterRegistryKey.MA_200_GUARD)).isEqualTo("QQQ < MA200 시 공격 버킷 1단계 축소");
        assertThat(snapshots.get(ParameterRegistryKey.ORDER_BUFFER_RETRY_POLICY))
                .isEqualTo("초기 BUY 0 tick / SELL 0 tick, 재시도 BUY +1 tick / SELL +1 tick, 최대 3회, 대기 2000ms");
        assertThat(snapshots.get(ParameterRegistryKey.MAX_ORDER_NOTIONAL_USD)).isEqualTo("미정의 (0으로 비활성)");
        assertThat(snapshots.get(ParameterRegistryKey.MAX_DAILY_TURNOVER_PCT)).isEqualTo("12.5 %");
        assertThat(snapshots.get(ParameterRegistryKey.MAX_RETRY_EXPOSURE_USD)).isEqualTo("미정의 (0으로 비활성)");
        assertThat(snapshots.get(ParameterRegistryKey.MAX_SLIPPAGE_PCT)).isEqualTo("0.8 %");
    }
}
