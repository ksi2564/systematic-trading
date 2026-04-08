package my.side.trading.adapter.in.web.dashboard.dto;

import my.side.trading.core.application.portfolio.PortfolioPerformanceReport;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSummary;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardPerformanceResponse(
        SummaryInfo summary,
        int dailySnapshotLimit,
        List<DailySnapshotInfo> recentDailySnapshots,
        List<MonthlyPnlInfo> monthlyPnls
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
            BigDecimal cumulativePnlPct
    ) {
        public static SummaryInfo from(PortfolioPerformanceSummary summary) {
            return new SummaryInfo(
                    summary.dataAvailable(),
                    summary.missingReason(),
                    summary.asOfDate(),
                    summary.latestNav(),
                    summary.peakNav(),
                    summary.currentDrawdownPct(),
                    summary.maxDrawdownPct(),
                    summary.cumulativePnlAmount(),
                    summary.cumulativePnlPct()
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

    public static DashboardPerformanceResponse from(PortfolioPerformanceReport report) {
        return new DashboardPerformanceResponse(
                SummaryInfo.from(report.summary()),
                report.dailySnapshotLimit(),
                report.recentDailySnapshots().stream()
                        .map(DailySnapshotInfo::from)
                        .toList(),
                report.monthlyPnls().stream()
                        .map(MonthlyPnlInfo::from)
                        .toList()
        );
    }
}
