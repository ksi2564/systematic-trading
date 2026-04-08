package my.side.trading.core.application.portfolio;

import my.side.trading.core.domain.portfolio.PortfolioSnapshot;

import java.util.List;

public record PortfolioPerformanceReport(
        PortfolioPerformanceSummary summary,
        int dailySnapshotLimit,
        List<PortfolioSnapshot> recentDailySnapshots,
        List<PortfolioPerformanceSummary.MonthlyPnl> monthlyPnls
) {
}
