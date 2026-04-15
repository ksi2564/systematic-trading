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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.scheduling", name = "enabled", havingValue = "true")
public class RebalanceExecutionScheduler {

    private final ExecutionGuard guard;
    private final RebalanceOrchestrator orchestrator;
    private final MarketCalendarService marketCalendarService;

    // KST 기준 미장 개장 이후 15분 여유
    // 정리 예정: 추후 계절시간을 반영해야 한다.
    @Scheduled(cron = "0 45 23 * * MON-FRI", zone = "Asia/Seoul") // 23:45 KST
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
        orchestrator.run(LocalDateTime.now(), ExecutionTriggerType.AUTOMATED);
    }
}
