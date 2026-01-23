package my.side.trading.adapter.in.web.dashboard.dto;

import lombok.Builder;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyState;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record DashboardResponse(
        Portfolio portfolio,
        StrategyState strategyState,
        CircuitBreakerInfo circuitBreaker,
        List<ExecutionJob> recentJobs) {
    @Builder
    public record CircuitBreakerInfo(
            BigDecimal vix,
            BigDecimal qqq200Ma) {
    }

    public static DashboardResponse of(
            Portfolio portfolio,
            StrategyState strategyState,
            BigDecimal vix,
            BigDecimal qqq200Ma,
            List<ExecutionJob> recentJobs) {
        return DashboardResponse.builder()
                .portfolio(portfolio)
                .strategyState(strategyState)
                .circuitBreaker(new CircuitBreakerInfo(vix, qqq200Ma))
                .recentJobs(recentJobs)
                .build();
    }
}
