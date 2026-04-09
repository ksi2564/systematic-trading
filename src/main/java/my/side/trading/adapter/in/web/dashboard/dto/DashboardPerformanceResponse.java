package my.side.trading.adapter.in.web.dashboard.dto;

import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsReport;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsSummary;
import my.side.trading.core.application.portfolio.PortfolioPerformanceReport;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSummary;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardPerformanceResponse(
        SummaryInfo summary,
        int dailySnapshotLimit,
        List<DailySnapshotInfo> recentDailySnapshots,
        List<MonthlyPnlInfo> monthlyPnls,
        List<DailyActualSnapshotInfo> recentDailyActualSnapshots,
        List<MonthlyActualAnalyticsInfo> monthlyActualAnalytics
) {
    public record SummaryInfo(
            boolean dataAvailable,
            String missingReason,
            LocalDate asOfDate,
            BigDecimal latestNav,
            BigDecimal peakNav,
            BigDecimal currentDrawdownPct,
            BigDecimal maxDrawdownPct,
            BigDecimal cumulativePnlAmount,
            BigDecimal cumulativePnlPct,
            ActualPerformanceInfo actualPerformanceUsd,
            ActualPerformanceInfo actualPerformanceKrw,
            CostBreakdownInfo costBreakdown,
            HoldingCostEstimateInfo holdingCostEstimate,
            AnalysisCoverageInfo analysisCoverage
    ) {
        public static SummaryInfo from(PortfolioPerformanceAnalyticsSummary summary) {
            PortfolioPerformanceSummary baseSummary = summary.baseSummary();
            return new SummaryInfo(
                    baseSummary.dataAvailable(),
                    baseSummary.missingReason(),
                    baseSummary.asOfDate(),
                    baseSummary.latestNav(),
                    baseSummary.peakNav(),
                    baseSummary.currentDrawdownPct(),
                    baseSummary.maxDrawdownPct(),
                    baseSummary.cumulativePnlAmount(),
                    baseSummary.cumulativePnlPct(),
                    ActualPerformanceInfo.from(summary.actualPerformanceUsd()),
                    ActualPerformanceInfo.from(summary.actualPerformanceKrw()),
                    CostBreakdownInfo.from(summary.costBreakdown()),
                    HoldingCostEstimateInfo.from(summary.holdingCostEstimate()),
                    AnalysisCoverageInfo.from(summary.analysisCoverage())
            );
        }
    }

    public record ActualPerformanceInfo(
            LocalDate asOfDate,
            BigDecimal latestNav,
            BigDecimal netActualPnlAmount,
            BigDecimal netActualPnlPct,
            BigDecimal realizedPnlAmount
    ) {
        public static ActualPerformanceInfo from(PortfolioPerformanceAnalyticsSummary.ActualPerformanceSummary summary) {
            return new ActualPerformanceInfo(
                    summary.asOfDate(),
                    summary.latestNav(),
                    summary.netActualPnlAmount(),
                    summary.netActualPnlPct(),
                    summary.realizedPnlAmount()
            );
        }
    }

    public record CostBreakdownInfo(
            BigDecimal brokerFeeUsd,
            BigDecimal brokerFeeKrw,
            BigDecimal taxUsd,
            BigDecimal taxKrw
    ) {
        public static CostBreakdownInfo from(PortfolioPerformanceAnalyticsSummary.CostBreakdown breakdown) {
            return new CostBreakdownInfo(
                    breakdown.brokerFeeUsd(),
                    breakdown.brokerFeeKrw(),
                    breakdown.taxUsd(),
                    breakdown.taxKrw()
            );
        }
    }

    public record HoldingCostEstimateInfo(
            boolean configured,
            BigDecimal totalEstimateUsd,
            BigDecimal totalEstimateKrw
    ) {
        public static HoldingCostEstimateInfo from(PortfolioPerformanceAnalyticsSummary.HoldingCostEstimate estimate) {
            return new HoldingCostEstimateInfo(
                    estimate.configured(),
                    estimate.totalEstimateUsd(),
                    estimate.totalEstimateKrw()
            );
        }
    }

    public record AnalysisCoverageInfo(
            String status,
            LocalDate startDate,
            LocalDate endDate,
            int totalSnapshotCount,
            int actualReadyCount,
            List<LocalDate> missingDates
    ) {
        public static AnalysisCoverageInfo from(PortfolioPerformanceAnalyticsSummary.AnalysisCoverage coverage) {
            return new AnalysisCoverageInfo(
                    coverage.status(),
                    coverage.startDate(),
                    coverage.endDate(),
                    coverage.totalSnapshotCount(),
                    coverage.actualReadyCount(),
                    coverage.missingDates()
            );
        }
    }

    public record DailySnapshotInfo(
            LocalDate asOfDate,
            BigDecimal totalValue,
            BigDecimal cash,
            BigDecimal wQqq,
            BigDecimal wQld,
            BigDecimal wTqqq,
            BigDecimal ddPercent
    ) {
        public static DailySnapshotInfo from(PortfolioSnapshot snapshot) {
            return new DailySnapshotInfo(
                    snapshot.asOfDate(),
                    snapshot.totalValue(),
                    snapshot.cash(),
                    snapshot.wQqq(),
                    snapshot.wQld(),
                    snapshot.wTqqq(),
                    snapshot.ddPercent()
            );
        }
    }

    public record MonthlyPnlInfo(
            String month,
            BigDecimal startNav,
            BigDecimal endNav,
            BigDecimal pnlAmount,
            BigDecimal pnlPct
    ) {
        public static MonthlyPnlInfo from(PortfolioPerformanceSummary.MonthlyPnl monthlyPnl) {
            return new MonthlyPnlInfo(
                    monthlyPnl.month(),
                    monthlyPnl.startNav(),
                    monthlyPnl.endNav(),
                    monthlyPnl.pnlAmount(),
                    monthlyPnl.pnlPct()
            );
        }
    }

    public record DailyActualSnapshotInfo(
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
        public static DailyActualSnapshotInfo from(PerformanceAnalyticsSnapshot snapshot) {
            return new DailyActualSnapshotInfo(
                    snapshot.asOfDate(),
                    snapshot.navUsd(),
                    snapshot.navKrw(),
                    snapshot.fxRate(),
                    snapshot.realizedPnlUsd(),
                    snapshot.realizedPnlKrw(),
                    snapshot.brokerFeeUsd(),
                    snapshot.brokerFeeKrw(),
                    snapshot.taxUsd(),
                    snapshot.taxKrw(),
                    snapshot.actualDataReady(),
                    snapshot.holdingCostEstimateUsd(),
                    snapshot.holdingCostEstimateKrw(),
                    snapshot.holdingCostConfigured()
            );
        }
    }

    public record MonthlyActualAnalyticsInfo(
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
        public static MonthlyActualAnalyticsInfo from(PortfolioPerformanceAnalyticsReport.ActualMonthlyAnalytics analytics) {
            return new MonthlyActualAnalyticsInfo(
                    analytics.month(),
                    analytics.totalSnapshotCount(),
                    analytics.actualReadyCount(),
                    analytics.netActualPnlUsd(),
                    analytics.netActualPnlKrw(),
                    analytics.realizedPnlUsd(),
                    analytics.realizedPnlKrw(),
                    analytics.brokerFeeUsd(),
                    analytics.brokerFeeKrw(),
                    analytics.taxUsd(),
                    analytics.taxKrw(),
                    analytics.holdingCostEstimateUsd(),
                    analytics.holdingCostEstimateKrw()
            );
        }
    }

    public static DashboardPerformanceResponse from(PortfolioPerformanceAnalyticsReport report) {
        return new DashboardPerformanceResponse(
                SummaryInfo.from(report.summary()),
                report.dailySnapshotLimit(),
                report.recentDailySnapshots().stream()
                        .map(DailySnapshotInfo::from)
                        .toList(),
                report.monthlyPnls().stream()
                        .map(MonthlyPnlInfo::from)
                        .toList(),
                report.recentDailyActualSnapshots().stream()
                        .map(DailyActualSnapshotInfo::from)
                        .toList(),
                report.monthlyActualAnalytics().stream()
                        .map(MonthlyActualAnalyticsInfo::from)
                        .toList()
        );
    }
}
