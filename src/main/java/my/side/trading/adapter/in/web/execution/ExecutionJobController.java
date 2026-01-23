package my.side.trading.adapter.in.web.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/jobs")
public class ExecutionJobController {

    private final ExecutionJobExecutor executor;
    private final RebalanceOrchestrator rebalanceOrchestrator;
    private final StrategyEodScheduler eodScheduler;

    @PostMapping("/{jobId}/execute")
    public ApiResponse<ExecutionJob> execute(@PathVariable Long jobId) {
        log.info("수동 작업 실행 요청: jobId={}", jobId);
        return ApiResponse.success(executor.execute(jobId, LocalDateTime.now()));
    }

    @PostMapping("/manual-rebalance")
    public ApiResponse<Void> manualRebalance() {
        log.info("수동 리밸런싱 트리거 요청됨.");
        rebalanceOrchestrator.run(LocalDateTime.now());
        return ApiResponse.success(null);
    }

    @PostMapping("/eod-calculation")
    public ApiResponse<Void> eodCalculation() {
        log.info("수동 EOD 계산 요청됨.");
        eodScheduler.runEod();
        return ApiResponse.success(null);
    }
}
