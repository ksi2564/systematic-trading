package my.side.trading.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.scheduling", name = "enabled", havingValue = "true")
public class RebalanceExecutionScheduler {

    private final ExecutionGuard guard;
    private final RebalanceOrchestrator orchestrator;

    // KST 기준 미장 개장 이후 15분 여유
    // TODO: 추후 계절시간 감안 필요
    @Scheduled(cron = "0 45 23 * * MON-FRI", zone = "Asia/Seoul") // 23:45 KST
    public void runRebalance() {
        if (!guard.isExecutionEnabled()) {
            log.info("[SCHED] execution disabled -> skip");
            return;
        }
        if (guard.isKillSwitchOn()) {
            log.info("[SCHED] kill switch ON -> skip");
            return;
        }
        orchestrator.run(LocalDateTime.now());
    }
}
