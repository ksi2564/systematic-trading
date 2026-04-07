package my.side.trading.adapter.in.web.dashboard.dto;

import lombok.Builder;
import my.side.trading.core.application.execution.ExecutionGuardSnapshot;
import my.side.trading.core.application.operation.OperationsKpiSnapshot;
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
            List<MonthlyPnlInfo> recentMonthlyPnl) {

        public static PerformanceInfo from(PortfolioPerformanceSummary summary) {
            return new PerformanceInfo(
                    summary.dataAvailable(),
                    summary.asOfDate(),
                    summary.latestNav(),
                    summary.peakNav(),
                    summary.currentDrawdownPct(),
                    summary.maxDrawdownPct(),
                    summary.cumulativePnlAmount(),
                    summary.cumulativePnlPct(),
                    summary.recentMonthlyPnl().stream()
                            .map(monthly -> new MonthlyPnlInfo(
                                    monthly.month(),
                                    monthly.startNav(),
                                    monthly.endNav(),
                                    monthly.pnlAmount(),
                                    monthly.pnlPct()))
                            .toList()
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
            PortfolioPerformanceSummary performance,
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
