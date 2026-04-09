package my.side.trading.core.application.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PortfolioPerformanceAnalyticsSummary(
        PortfolioPerformanceSummary baseSummary,
        ActualPerformanceSummary actualPerformanceUsd,
        ActualPerformanceSummary actualPerformanceKrw,
        CostBreakdown costBreakdown,
        HoldingCostEstimate holdingCostEstimate,
        AnalysisCoverage analysisCoverage
) {
    public record ActualPerformanceSummary(
            LocalDate asOfDate,
            BigDecimal latestNav,
            BigDecimal netActualPnlAmount,
            BigDecimal netActualPnlPct,
            BigDecimal realizedPnlAmount
    ) {
    }

    public record CostBreakdown(
            BigDecimal brokerFeeUsd,
            BigDecimal brokerFeeKrw,
            BigDecimal taxUsd,
            BigDecimal taxKrw
    ) {
    }

    public record HoldingCostEstimate(
            boolean configured,
            BigDecimal totalEstimateUsd,
            BigDecimal totalEstimateKrw
    ) {
    }

    public record AnalysisCoverage(
            String status,
            LocalDate startDate,
            LocalDate endDate,
            int totalSnapshotCount,
            int actualReadyCount,
            List<LocalDate> missingDates
    ) {
    }
}
