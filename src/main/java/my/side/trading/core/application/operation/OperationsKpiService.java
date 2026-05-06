package my.side.trading.core.application.operation;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class OperationsKpiService {

    private final StrategyStateRepository strategyStateRepository;
    private final ExecutionJobRepository executionJobRepository;
    private final MarketCalendarService marketCalendarService;
    private final TradingOperationProps operationProps;

    public OperationsKpiSnapshot snapshot() {
        LocalDate marketDate = marketCalendarService.currentMarketDate();
        var marketStatus = marketCalendarService.getMarketStatus(marketDate);
        var latestState = strategyStateRepository.findLatestState().orElse(null);
        List<ExecutionJob> jobs = executionJobRepository.findAll();

        boolean latestEodSuccess = !marketStatus.allowsScheduledEod()
                || (latestState != null && marketDate.equals(latestState.asOfDate()));

        int duplicateSignalJobCount = duplicateSignalJobCount(jobs);
        int unresolvedOrderCount = countOrders(jobs, status ->
                status == ExecutionOrderStatus.REQUESTED
                        || status == ExecutionOrderStatus.REJECTED
                        || status == ExecutionOrderStatus.CONFIRMATION_REQUIRED);
        int rejectedOrderCount = countOrders(jobs, status -> status == ExecutionOrderStatus.REJECTED);
        int attemptedOrderCount = countOrders(jobs, status ->
                status == ExecutionOrderStatus.ACCEPTED
                        || status == ExecutionOrderStatus.REJECTED
                        || status == ExecutionOrderStatus.CANCELED);
        BigDecimal orderFailureRatePct = calculateFailureRate(rejectedOrderCount, attemptedOrderCount);

        List<OperationsKpiBreach> breaches = detectBreaches(
                latestEodSuccess,
                duplicateSignalJobCount,
                unresolvedOrderCount,
                orderFailureRatePct);

        return new OperationsKpiSnapshot(
                marketDate,
                marketStatus,
                latestState != null ? latestState.asOfDate() : null,
                latestEodSuccess,
                duplicateSignalJobCount,
                unresolvedOrderCount,
                rejectedOrderCount,
                attemptedOrderCount,
                orderFailureRatePct,
                !breaches.isEmpty(),
                List.copyOf(breaches));
    }

    public boolean hasAutoLiveBreach() {
        return snapshot().breached();
    }

    private List<OperationsKpiBreach> detectBreaches(
            boolean latestEodSuccess,
            int duplicateSignalJobCount,
            int unresolvedOrderCount,
            BigDecimal orderFailureRatePct
    ) {
        TradingOperationProps.KpiProps kpi = operationProps.kpi();
        List<OperationsKpiBreach> breaches = new ArrayList<>();
        if (kpi.requireLatestEodSuccess() && !latestEodSuccess) {
            breaches.add(OperationsKpiBreach.LATEST_EOD_MISSING);
        }
        if (duplicateSignalJobCount > kpi.maxDuplicateSignalJobs()) {
            breaches.add(OperationsKpiBreach.DUPLICATE_SIGNAL_JOB_DETECTED);
        }
        if (unresolvedOrderCount > kpi.maxUnresolvedOrders()) {
            breaches.add(OperationsKpiBreach.UNRESOLVED_ORDERS_PRESENT);
        }
        if (orderFailureRatePct.compareTo(kpi.maxOrderFailureRatePct()) > 0) {
            breaches.add(OperationsKpiBreach.ORDER_FAILURE_RATE_EXCEEDED);
        }
        return breaches;
    }

    private int duplicateSignalJobCount(List<ExecutionJob> jobs) {
        Map<LocalDate, Long> grouped = jobs.stream()
                .collect(Collectors.groupingBy(ExecutionJob::getSignalDate, Collectors.counting()));
        return grouped.values().stream()
                .mapToInt(count -> count > 1 ? Math.toIntExact(count - 1) : 0)
                .sum();
    }

    private int countOrders(List<ExecutionJob> jobs, Predicate<ExecutionOrderStatus> predicate) {
        return jobs.stream()
                .flatMap(job -> job.getOrders().stream())
                .map(ExecutionOrder::getStatus)
                .filter(predicate)
                .mapToInt(ignored -> 1)
                .sum();
    }

    private BigDecimal calculateFailureRate(int rejectedOrderCount, int attemptedOrderCount) {
        if (attemptedOrderCount == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(rejectedOrderCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(attemptedOrderCount), 2, RoundingMode.HALF_UP);
    }
}
