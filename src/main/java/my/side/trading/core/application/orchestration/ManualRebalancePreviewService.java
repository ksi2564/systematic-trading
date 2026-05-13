package my.side.trading.core.application.orchestration;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.MarketQuoteUnavailableException;
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
import my.side.trading.core.infrastructure.config.TradingStrategyProps;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
    private final TradingStrategyProps strategyProps;

    public ManualRebalancePreview preview() {
        Instant generatedAt = clock.instant();
        StrategyState state = strategyStateRepository.findLatestState()
                .orElseThrow(() -> new IllegalStateException("StrategyState가 없습니다. EOD가 먼저 수행되어야 합니다."));
        var prevWeights = strategyStateRepository.findPreviousState(state.asOfDate())
                .map(StrategyState::targetWeights)
                .orElse(null);

        Portfolio portfolio = portfolioService.getCurrentPortfolio();
        BigDecimal vix = marketDataProvider.getVixPrice().orElse(null);
        BigDecimal signal200Ma = marketDataProvider.getSignal200Ma(strategyProps.signalSymbol()).orElse(null);
        RebalanceDecision decision = decisionService.decide(state, portfolio, prevWeights, vix, signal200Ma);
        boolean duplicateSignalJobExists = jobRepository.findBySignalDate(state.asOfDate()).isPresent();
        ExecutionBlockReason manualBlockReason = executionGuard
                .getExecutionBlockReason(ExecutionTriggerType.MANUAL)
                .orElse(null);

        RebalanceOrderPlan orderPlan = planOrders(state.asOfDate(), decision, portfolio);
        if (orderPlan.quoteUnavailable() && manualBlockReason == null) {
            manualBlockReason = ExecutionBlockReason.MARKET_QUOTE_UNAVAILABLE;
        }
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
                strategyProps.signalSymbol(),
                strategyProps.signalSymbol(),
                vix,
                signal200Ma,
                duplicateSignalJobExists,
                orderPlan.orders(),
                orderPlan.totalOrderNotional(),
                orderPlan.estimatedRemainingCash(),
                executable);
    }

    private RebalanceOrderPlan planOrders(
            LocalDate signalDate,
            RebalanceDecision decision,
            Portfolio portfolio
    ) {
        try {
            return orderPlanner.plan(signalDate, decision, portfolio);
        } catch (MarketQuoteUnavailableException e) {
            return new RebalanceOrderPlan(
                    List.of(),
                    BigDecimal.ZERO,
                    defaultZero(portfolio.cash()),
                    true);
        }
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
