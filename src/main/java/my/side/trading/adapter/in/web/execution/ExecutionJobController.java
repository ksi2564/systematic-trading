package my.side.trading.adapter.in.web.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/execution/jobs")
public class ExecutionJobController {

    private final ExecutionJobExecutor executor;

    @PostMapping("/{jobId}/execute")
    public ExecutionJob execute(@PathVariable Long jobId) {
        return executor.execute(jobId, LocalDateTime.now());
    }
}
