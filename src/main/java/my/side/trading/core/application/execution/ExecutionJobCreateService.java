package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.portfolio.Portfolio;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
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

        List<ExecutionOrder> orders = new ArrayList<>();
        BigDecimal remainingCash = portfolio.cash();

        for (OrderIntent intent : decision.intents()) {
            OrderAndCashDelta ocd = orderFactory.fromIntentWithCashDelta(intent, portfolio, remainingCash)
                    .orElse(null);
            if (ocd == null) continue;

            orders.add(ocd.order());
            // BUY면 음수, SELL이면 양수
            remainingCash = remainingCash.add(ocd.cashDelta());

            if (remainingCash.signum() < 0) {
                log.warn(
                        "remainingCash 0 이하로 떨어짐. 0으로 변환 후 진행 | " +
                                "symbol={}, side={}, qty={}, limitPrice={}, cashDelta={}, cashBefore={}",
                        ocd.order().getSymbol(),
                        ocd.order().getSide(),
                        ocd.order().getQuantity(),
                        ocd.order().getLimitPrice(),
                        ocd.cashDelta(),
                        remainingCash.subtract(ocd.cashDelta())
                );
                remainingCash = BigDecimal.ZERO;
            }

            log.info("planned order: sym={}, side={}, qty={}, limit={}, cashDelta={}, remainingCash={}",
                    ocd.order().getSymbol(),
                    ocd.order().getSide(),
                    ocd.order().getQuantity(),
                    ocd.order().getLimitPrice(),
                    ocd.cashDelta(),
                    remainingCash
            );
        }

        if (orders.isEmpty()) {
            log.info("skip job creation: 실행 가능한 주문이 없습니다. intents={}, reason={}",
                    decision.intents().size(), decision.reason());
            return Optional.empty();
        }

        Optional<ExecutionJob> existing = jobRepository.findBySignalDate(signalDate);
        if (existing.isPresent()) {
            log.warn("job이 이미 존재합니다: signalDate={}, orders={}", signalDate, orders.size());
            return Optional.empty();
        }

        ExecutionJob job = ExecutionJob.create(signalDate, executeAfter, orders);
        ExecutionJob saved = jobRepository.save(job);

        log.info("job created: jobId={}, orders={}", saved.getId(), orders.size());
        return Optional.of(saved);
    }
}
