package my.side.trading.core.application.orchestration;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.RebalanceDecisionService;
import my.side.trading.core.application.execution.RebalanceOrderPlan;
import my.side.trading.core.application.execution.RebalanceOrderPlanner;
import my.side.trading.core.application.port.out.MarketDataProvider;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ManualRebalancePreviewService {

    private final StrategyStateRepository strategyStateRepository;
    private final PortfolioService portfolioService;
    private final RebalanceDecisionService decisionService;
    private final MarketDataProvider marketDataProvider;
    private final ExecutionJobRepository jobRepository;
    private final ExecutionGuard executionGuard;
    private final RebalanceOrderPlanner orderPlanner;
    private final Clock clock;

    public ManualRebalancePreview preview() {
        Instant generatedAt = clock.instant();
        StrategyState state = strategyStateRepository.findLatestState()
                .orElseThrow(() -> new IllegalStateException("StrategyState가 없습니다. EOD가 먼저 수행되어야 합니다."));
        var prevWeights = strategyStateRepository.findPreviousState(state.asOfDate())
                .map(StrategyState::targetWeights)
                .orElse(null);

        Portfolio portfolio = portfolioService.getCurrentPortfolio();
        BigDecimal vix = marketDataProvider.getVixPrice().orElse(null);
        BigDecimal qqq200Ma = marketDataProvider.getQqq200Ma().orElse(null);
        RebalanceDecision decision = decisionService.decide(state, portfolio, prevWeights, vix, qqq200Ma);
        boolean duplicateSignalJobExists = jobRepository.findBySignalDate(state.asOfDate()).isPresent();
        ExecutionBlockReason manualBlockReason = executionGuard
                .getExecutionBlockReason(ExecutionTriggerType.MANUAL)
                .orElse(null);

        RebalanceOrderPlan orderPlan = orderPlanner.plan(state.asOfDate(), decision, portfolio);
        boolean executable = manualBlockReason == null
                && !duplicateSignalJobExists
                && decision.shouldRebalance()
                && !orderPlan.orders().isEmpty()
                && !orderPlan.hasRiskViolation();

        return new ManualRebalancePreview(
                generatedAt,
                executionGuard.currentMode(),
                manualBlockReason,
                state.asOfDate(),
                state,
                decision,
                portfolio,
                vix,
                qqq200Ma,
                duplicateSignalJobExists,
                orderPlan.orders(),
                orderPlan.totalOrderNotional(),
                orderPlan.estimatedRemainingCash(),
                executable);
    }
}
