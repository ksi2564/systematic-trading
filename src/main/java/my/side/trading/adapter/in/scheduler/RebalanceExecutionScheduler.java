package my.side.trading.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.scheduling", name = "enabled", havingValue = "true")
public class RebalanceExecutionScheduler {

    static final String MARKET_TIME_ZONE = "America/New_York";
    static final String REBALANCE_CRON = "0 45 9 * * MON-FRI";

    private final ExecutionGuard guard;
    private final RebalanceOrchestrator orchestrator;
    private final MarketCalendarService marketCalendarService;
    private final Clock clock;

    // 미국 동부시간 기준 정규장 개장 15분 뒤에 실행한다.
    @Scheduled(cron = REBALANCE_CRON, zone = MARKET_TIME_ZONE)
    public void runRebalance() {
        LocalDate marketDate = marketCalendarService.currentMarketDate();
        var marketStatus = marketCalendarService.getMarketStatus(marketDate);
        if (!marketStatus.allowsAutomatedRebalance()) {
            log.info("[SCHED] 시장 캘린더 기준으로 자동 리밸런싱을 건너뜁니다 | marketDate={}, marketStatus={}, mode={}",
                    marketDate,
                    marketStatus,
                    guard.currentMode());
            return;
        }

        var blockReason = guard.getExecutionBlockReason(ExecutionTriggerType.AUTOMATED);
        if (blockReason.isPresent()) {
            log.info("[SCHED] 자동 리밸런싱이 차단되어 건너뜁니다 | reason={}, mode={}",
                    blockReason.get().code(),
                    guard.currentMode());
            return;
        }
        orchestrator.run(LocalDateTime.now(clock), ExecutionTriggerType.AUTOMATED);
    }
}
