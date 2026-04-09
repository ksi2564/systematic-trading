package my.side.trading.core.application.portfolio;

import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioPerformanceAnalyticsReport(
        PortfolioPerformanceAnalyticsSummary summary,
        int dailySnapshotLimit,
        List<PortfolioSnapshot> recentDailySnapshots,
        List<PortfolioPerformanceSummary.MonthlyPnl> monthlyPnls,
        List<PerformanceAnalyticsSnapshot> recentDailyActualSnapshots,
        List<ActualMonthlyAnalytics> monthlyActualAnalytics
) {
    public record ActualMonthlyAnalytics(
            String month,
            int totalSnapshotCount,
            int actualReadyCount,
            BigDecimal netActualPnlUsd,
            BigDecimal netActualPnlKrw,
            BigDecimal realizedPnlUsd,
            BigDecimal realizedPnlKrw,
            BigDecimal brokerFeeUsd,
            BigDecimal brokerFeeKrw,
            BigDecimal taxUsd,
            BigDecimal taxKrw,
            BigDecimal holdingCostEstimateUsd,
            BigDecimal holdingCostEstimateKrw
    ) {
    }
}
