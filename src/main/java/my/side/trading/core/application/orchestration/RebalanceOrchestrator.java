package my.side.trading.core.application.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.application.execution.ExecutionBlockReason;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.ExecutionJobCreateService;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.execution.RebalanceDecisionService;
import my.side.trading.core.application.port.out.MarketDataProvider;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RebalanceOrchestrator {

    private final StrategyStateRepository strategyStateRepository;
    private final PortfolioService portfolioService;
    private final RebalanceDecisionService decisionService;
    private final ExecutionJobCreateService jobCreateService;
    private final ExecutionJobExecutor jobExecutor;
    private final MarketDataProvider marketDataProvider;
    private final ExecutionGuard executionGuard;

    /**
     * 자동 매매 시작점
     *
     * @param now
     */
    public RebalanceRunResult run(LocalDateTime now) {
        return run(now, ExecutionTriggerType.MANUAL);
    }

    public RebalanceRunResult run(LocalDateTime now, ExecutionTriggerType triggerType) {
        StrategyState state = strategyStateRepository.findLatestState()
                .orElseThrow(() -> new IllegalStateException("StrategyState가 없습니다. EOD가 먼저 수행되어야 합니다."));
        var prevWeights = strategyStateRepository.findPreviousState(state.asOfDate())
                .map(StrategyState::targetWeights)
                .orElse(null);

        Portfolio portfolio = portfolioService.getCurrentPortfolio();

        // 서킷 브레이커 판단용 VIX와 200MA를 포트 인터페이스로 조회한다.
        BigDecimal vix = marketDataProvider.getVixPrice().orElse(null);
        BigDecimal qqqMa200 = marketDataProvider.getQqq200Ma().orElse(null);
        log.info("서킷 브레이커 데이터: VIX={}, QQQ_200MA={}", vix, qqqMa200);

        RebalanceDecision decision = decisionService.decide(state, portfolio, prevWeights, vix, qqqMa200);
        if (!decision.shouldRebalance()) {
            log.info("리밸런싱을 건너뜁니다: {}", decision.reason());
            return RebalanceRunResult.skipped(triggerType, executionGuard.currentMode(), decision.reason());
        }

        LocalDate signalDate = state.asOfDate();
        LocalDateTime executeAfter = now; // 즉시 실행(추후 규칙화)

        return jobCreateService.createJob(signalDate, executeAfter, decision, portfolio)
                .map(job -> handleCreatedJob(now, triggerType, decision, job))
                .orElseGet(() -> RebalanceRunResult.skipped(
                        triggerType,
                        executionGuard.currentMode(),
                        decision.reason() + " [job not created]"));
    }

    private RebalanceRunResult handleCreatedJob(
            LocalDateTime now,
            ExecutionTriggerType triggerType,
            RebalanceDecision decision,
            my.side.trading.core.domain.execution.order.ExecutionJob job) {
        log.info("리밸런싱 job을 계획했습니다: jobId={}, triggerType={}", job.getId(), triggerType);

        var blockReason = executionGuard.getExecutionBlockReason(triggerType);
        if (blockReason.isPresent()) {
            ExecutionBlockReason reason = blockReason.get();
            log.info("리밸런싱 실행을 보류합니다: jobId={}, reason={}", job.getId(), reason.code());
            return RebalanceRunResult.plannedOnly(
                    triggerType,
                    executionGuard.currentMode(),
                    job.getId(),
                    decision.reason(),
                    reason);
        }

        jobExecutor.execute(job.getId(), now, triggerType);
        return RebalanceRunResult.executed(
                triggerType,
                executionGuard.currentMode(),
                job.getId(),
                decision.reason());
    }
}
