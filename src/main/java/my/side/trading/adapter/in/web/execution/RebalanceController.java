package my.side.trading.adapter.in.web.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/execution/rebalance")
public class RebalanceController {

    private final RebalanceOrchestrator orchestrator;

    @GetMapping("/run")
    public void run() {
        orchestrator.run(LocalDateTime.now());
    }
}
