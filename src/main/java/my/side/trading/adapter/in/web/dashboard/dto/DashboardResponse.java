package my.side.trading.adapter.in.web.dashboard.dto;

import lombok.Builder;
import my.side.trading.core.application.execution.ExecutionGuardSnapshot;
import my.side.trading.core.application.operation.OperationsKpiSnapshot;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsSummary;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSummary;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyState;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record DashboardResponse(
        Portfolio portfolio,
        StrategyState strategyState,
        CircuitBreakerInfo circuitBreaker,
        OperationsKpiSnapshot operationsKpi,
        PerformanceInfo performance,
        OperatingMode operatingMode,
        ExecutionGuardSnapshot guard,
        List<ExecutionJob> recentJobs,
        List<OperatingModeAuditEvent> recentOperatingModeAudits) {
    @Builder
    public record CircuitBreakerInfo(
            BigDecimal vix,
            BigDecimal qqq200Ma) {
    }

    @Builder
    public record PerformanceInfo(
            boolean dataAvailable,
            java.time.LocalDate asOfDate,
            BigDecimal latestNav,
            BigDecimal peakNav,
            BigDecimal currentDrawdownPct,
            BigDecimal maxDrawdownPct,
            BigDecimal cumulativePnlAmount,
            BigDecimal cumulativePnlPct,
            List<MonthlyPnlInfo> recentMonthlyPnl,
            ActualPerformanceInfo actualPerformanceUsd,
            ActualPerformanceInfo actualPerformanceKrw,
            CostBreakdownInfo costBreakdown,
            HoldingCostEstimateInfo holdingCostEstimate,
            AnalysisCoverageInfo analysisCoverage) {

        public static PerformanceInfo from(PortfolioPerformanceAnalyticsSummary summary) {
            PortfolioPerformanceSummary baseSummary = summary.baseSummary();
            return new PerformanceInfo(
                    baseSummary.dataAvailable(),
                    baseSummary.asOfDate(),
                    baseSummary.latestNav(),
                    baseSummary.peakNav(),
                    baseSummary.currentDrawdownPct(),
                    baseSummary.maxDrawdownPct(),
                    baseSummary.cumulativePnlAmount(),
                    baseSummary.cumulativePnlPct(),
                    baseSummary.recentMonthlyPnl().stream()
                            .map(monthly -> new MonthlyPnlInfo(
                                    monthly.month(),
                                    monthly.startNav(),
                                    monthly.endNav(),
                                    monthly.pnlAmount(),
                                    monthly.pnlPct()))
                            .toList(),
                    ActualPerformanceInfo.from(summary.actualPerformanceUsd()),
                    ActualPerformanceInfo.from(summary.actualPerformanceKrw()),
                    CostBreakdownInfo.from(summary.costBreakdown()),
                    HoldingCostEstimateInfo.from(summary.holdingCostEstimate()),
                    AnalysisCoverageInfo.from(summary.analysisCoverage())
            );
        }
    }

    @Builder
    public record ActualPerformanceInfo(
            java.time.LocalDate asOfDate,
            BigDecimal latestNav,
            BigDecimal netActualPnlAmount,
            BigDecimal netActualPnlPct,
            BigDecimal realizedPnlAmount) {
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

    @Builder
    public record CostBreakdownInfo(
            BigDecimal brokerFeeUsd,
            BigDecimal brokerFeeKrw,
            BigDecimal taxUsd,
            BigDecimal taxKrw) {
        public static CostBreakdownInfo from(PortfolioPerformanceAnalyticsSummary.CostBreakdown breakdown) {
            return new CostBreakdownInfo(
                    breakdown.brokerFeeUsd(),
                    breakdown.brokerFeeKrw(),
                    breakdown.taxUsd(),
                    breakdown.taxKrw()
            );
        }
    }

    @Builder
    public record HoldingCostEstimateInfo(
            boolean configured,
            BigDecimal totalEstimateUsd,
            BigDecimal totalEstimateKrw) {
        public static HoldingCostEstimateInfo from(PortfolioPerformanceAnalyticsSummary.HoldingCostEstimate estimate) {
            return new HoldingCostEstimateInfo(
                    estimate.configured(),
                    estimate.totalEstimateUsd(),
                    estimate.totalEstimateKrw()
            );
        }
    }

    @Builder
    public record AnalysisCoverageInfo(
            String status,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            int totalSnapshotCount,
            int actualReadyCount,
            List<java.time.LocalDate> missingDates) {
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

    @Builder
    public record MonthlyPnlInfo(
            String month,
            BigDecimal startNav,
            BigDecimal endNav,
            BigDecimal pnlAmount,
            BigDecimal pnlPct) {
    }

    public static DashboardResponse of(
            Portfolio portfolio,
            StrategyState strategyState,
            BigDecimal vix,
            BigDecimal qqq200Ma,
            OperationsKpiSnapshot operationsKpi,
            PortfolioPerformanceAnalyticsSummary performance,
            OperatingMode operatingMode,
            ExecutionGuardSnapshot guard,
            List<ExecutionJob> recentJobs,
            List<OperatingModeAuditEvent> recentOperatingModeAudits) {
        return DashboardResponse.builder()
                .portfolio(portfolio)
                .strategyState(strategyState)
                .circuitBreaker(new CircuitBreakerInfo(vix, qqq200Ma))
                .operationsKpi(operationsKpi)
                .performance(PerformanceInfo.from(performance))
                .operatingMode(operatingMode)
                .guard(guard)
                .recentJobs(recentJobs)
                .recentOperatingModeAudits(recentOperatingModeAudits)
                .build();
    }
}
