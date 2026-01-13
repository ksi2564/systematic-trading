package my.side.trading.core.application.orchestration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.yahoo.YahooVixService;
import my.side.trading.core.application.execution.ExecutionJobCreateService;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.execution.RebalanceDecisionService;
import my.side.trading.core.application.portfolio.PortfolioService;
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
    private final YahooVixService yahooVixService;

    /**
     * 자동 매매 시작점
     *
     * @param now
     */
    public void run(LocalDateTime now) {
        StrategyState state = strategyStateRepository.findLatestState()
                .orElseThrow(() -> new IllegalStateException("StrategyState가 없습니다. EOD가 먼저 수행되어야 합니다."));

        Portfolio portfolio = portfolioService.getCurrentPortfolio();

        // Circuit Breaker용 VIX 및 200MA 조회
        BigDecimal vix = yahooVixService.getVixPrice().orElse(null);
        BigDecimal qqqMa200 = yahooVixService.getQqq200Ma().orElse(null);
        log.info("Circuit Breaker data: VIX={}, QQQ_200MA={}", vix, qqqMa200);

        RebalanceDecision decision = decisionService.decide(state, portfolio, vix, qqqMa200);
        if (!decision.shouldRebalance()) {
            log.info("skip rebalance: {}", decision.reason());
            return;
        }

        LocalDate signalDate = state.asOfDate();
        LocalDateTime executeAfter = now; // 즉시 실행(추후 규칙화)

        jobCreateService.createJob(signalDate, executeAfter, decision, portfolio)
                .ifPresent(job -> {
                    log.info("rebalance job planned: jobId={}", job.getId());
                    jobExecutor.execute(job.getId(), now);
                });
    }
}
