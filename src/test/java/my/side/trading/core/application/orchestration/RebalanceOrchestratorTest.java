package my.side.trading.core.application.orchestration;

import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.ExecutionJobCreateService;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.execution.RebalanceDecisionService;
import my.side.trading.core.application.port.out.MarketDataProvider;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.core.domain.strategy.WeightSet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RebalanceOrchestratorTest {

    @Test
    void manual_trigger가_차단되면_job만_생성하고_execute는_호출하지_않는다() {
        StrategyStateRepository stateRepository = mock(StrategyStateRepository.class);
        PortfolioService portfolioService = mock(PortfolioService.class);
        RebalanceDecisionService decisionService = mock(RebalanceDecisionService.class);
        ExecutionJobCreateService jobCreateService = mock(ExecutionJobCreateService.class);
        ExecutionJobExecutor jobExecutor = mock(ExecutionJobExecutor.class);
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);

        StrategyState state = sampleState();
        Portfolio portfolio = new Portfolio(new BigDecimal("1000"), List.of());
        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "rebalance needed",
                WeightSet.of(100, 0, 0),
                List.of(new OrderIntent("QQQ", ExecutionOrderSide.BUY, "buy")));
        ExecutionJob job = sampleJob();

        when(stateRepository.findLatestState()).thenReturn(Optional.of(state));
        when(portfolioService.getCurrentPortfolio()).thenReturn(portfolio);
        when(marketDataProvider.getVixPrice()).thenReturn(Optional.empty());
        when(marketDataProvider.getQqq200Ma()).thenReturn(Optional.empty());
        when(decisionService.decide(state, portfolio, null, null)).thenReturn(decision);
        when(jobCreateService.createJob(eq(state.asOfDate()), any(), eq(decision), eq(portfolio)))
                .thenReturn(Optional.of(job));
        when(executionGuard.getExecutionBlockReason(ExecutionTriggerType.MANUAL))
                .thenReturn(Optional.of(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION));
        when(executionGuard.currentMode()).thenReturn(OperatingMode.PAPER);

        RebalanceOrchestrator orchestrator = new RebalanceOrchestrator(
                stateRepository,
                portfolioService,
                decisionService,
                jobCreateService,
                jobExecutor,
                marketDataProvider,
                executionGuard);

        RebalanceRunResult result = orchestrator.run(LocalDateTime.of(2026, 4, 2, 9, 0), ExecutionTriggerType.MANUAL);

        assertThat(result.jobCreated()).isTrue();
        assertThat(result.executed()).isFalse();
        assertThat(result.executionBlockReason()).isEqualTo(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION);
        verify(jobExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void automated_trigger가_허용되면_job을_즉시_실행한다() {
        StrategyStateRepository stateRepository = mock(StrategyStateRepository.class);
        PortfolioService portfolioService = mock(PortfolioService.class);
        RebalanceDecisionService decisionService = mock(RebalanceDecisionService.class);
        ExecutionJobCreateService jobCreateService = mock(ExecutionJobCreateService.class);
        ExecutionJobExecutor jobExecutor = mock(ExecutionJobExecutor.class);
        MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);

        StrategyState state = sampleState();
        Portfolio portfolio = new Portfolio(new BigDecimal("1000"), List.of());
        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "rebalance needed",
                WeightSet.of(100, 0, 0),
                List.of(new OrderIntent("QQQ", ExecutionOrderSide.BUY, "buy")));
        ExecutionJob job = sampleJob();
        LocalDateTime now = LocalDateTime.of(2026, 4, 2, 23, 45);

        when(stateRepository.findLatestState()).thenReturn(Optional.of(state));
        when(portfolioService.getCurrentPortfolio()).thenReturn(portfolio);
        when(marketDataProvider.getVixPrice()).thenReturn(Optional.empty());
        when(marketDataProvider.getQqq200Ma()).thenReturn(Optional.empty());
        when(decisionService.decide(state, portfolio, null, null)).thenReturn(decision);
        when(jobCreateService.createJob(state.asOfDate(), now, decision, portfolio)).thenReturn(Optional.of(job));
        when(executionGuard.getExecutionBlockReason(ExecutionTriggerType.AUTOMATED)).thenReturn(Optional.empty());
        when(executionGuard.currentMode()).thenReturn(OperatingMode.AUTO_LIVE);

        RebalanceOrchestrator orchestrator = new RebalanceOrchestrator(
                stateRepository,
                portfolioService,
                decisionService,
                jobCreateService,
                jobExecutor,
                marketDataProvider,
                executionGuard);

        RebalanceRunResult result = orchestrator.run(now, ExecutionTriggerType.AUTOMATED);

        assertThat(result.jobCreated()).isTrue();
        assertThat(result.executed()).isTrue();
        assertThat(result.executionBlockReason()).isNull();
        verify(jobExecutor).execute(job.getId(), now, ExecutionTriggerType.AUTOMATED);
    }

    private StrategyState sampleState() {
        return new StrategyState(
                LocalDate.of(2026, 4, 1),
                new BigDecimal("500"),
                new BigDecimal("450"),
                new BigDecimal("10"),
                new BigDecimal("15"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                WeightSet.of(100, 0, 0),
                true,
                1);
    }

    private ExecutionJob sampleJob() {
        return ExecutionJob.rehydrate(
                10L,
                LocalDate.of(2026, 4, 1),
                LocalDateTime.of(2026, 4, 2, 23, 45),
                my.side.trading.core.domain.execution.order.ExecutionStatus.PENDING,
                List.of(ExecutionOrder.rehydrate(
                        1L,
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        1,
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        ExecutionOrderStatus.PLANNED,
                        null,
                        null)),
                null,
                null);
    }
}
