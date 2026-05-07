package my.side.trading.adapter.in.web.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.in.web.execution.dto.OrderConfirmationResponse;
import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.execution.ExecutionOrderConfirmationService;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import my.side.trading.core.application.orchestration.RebalanceRunResult;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/jobs")
public class ExecutionJobController {

    private final ExecutionJobExecutor executor;
    private final ExecutionOrderConfirmationService confirmationService;
    private final RebalanceOrchestrator rebalanceOrchestrator;
    private final StrategyEodScheduler eodScheduler;
    private final Clock clock;

    @PostMapping("/{jobId}/execute")
    public ApiResponse<ExecutionJob> execute(@PathVariable Long jobId) {
        log.info("수동 작업 실행 요청: jobId={}", jobId);
        return ApiResponse.success(executor.execute(jobId, now(), ExecutionTriggerType.MANUAL));
    }

    @PostMapping("/{jobId}/orders/{orderId}/confirm")
    public ApiResponse<OrderConfirmationResponse> confirmOrder(
            @PathVariable Long jobId,
            @PathVariable Long orderId
    ) {
        log.info("주문 확인 요청: jobId={}, orderId={}", jobId, orderId);
        return ApiResponse.success(OrderConfirmationResponse.from(
                confirmationService.confirm(jobId, orderId, now())));
    }

    @PostMapping("/manual-rebalance")
    public ApiResponse<RebalanceRunResult> manualRebalance() {
        log.info("수동 리밸런싱 트리거 요청됨.");
        return ApiResponse.success(rebalanceOrchestrator.run(now(), ExecutionTriggerType.MANUAL));
    }

    @PostMapping("/eod-calculation")
    public ApiResponse<Void> eodCalculation() {
        log.info("수동 EOD 계산 요청됨.");
        eodScheduler.runManualEod();
        return ApiResponse.success(null);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
