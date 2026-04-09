package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PerformanceAnalyticsSnapshot(
        LocalDate asOfDate,
        BigDecimal navUsd,
        BigDecimal navKrw,
        BigDecimal fxRate,
        BigDecimal realizedPnlUsd,
        BigDecimal realizedPnlKrw,
        BigDecimal brokerFeeUsd,
        BigDecimal brokerFeeKrw,
        BigDecimal taxUsd,
        BigDecimal taxKrw,
        boolean actualDataReady,
        BigDecimal holdingCostEstimateUsd,
        BigDecimal holdingCostEstimateKrw,
        boolean holdingCostConfigured
) {
}
