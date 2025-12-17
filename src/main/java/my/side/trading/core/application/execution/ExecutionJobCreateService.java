package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class ExecutionJobCreateService {

    private final ExecutionOrderFactory orderFactory;
    private final ExecutionJobRepository jobRepository;

    public Optional<ExecutionJob> createJob(
            LocalDate signalDate,
            LocalDateTime executeAfter,
            RebalanceDecision decision,
            Portfolio portfolio
    ) {
        if (!decision.shouldRebalance()) {
            throw new IllegalArgumentException("shouldRebalance=false decision으로 job 생성 불가");
        }

        List<ExecutionOrder> orders = decision.intents().stream()
                .map(i -> orderFactory.fromIntent(i, portfolio))
                .flatMap(Optional::stream)
                .toList();

        if (orders.isEmpty()) {
            log.info("skip job creation: 실행 가능한 주문이 없습니다. intents={}, reason={}",
                    decision.intents().size(), decision.reason());
            return Optional.empty();
        }

        ExecutionJob job = ExecutionJob.create(signalDate, executeAfter, orders);
        ExecutionJob saved = jobRepository.save(job);

        log.info("job created: jobId={}, orders={}", saved.getId(), orders.size());
        return Optional.of(saved);
    }
}
