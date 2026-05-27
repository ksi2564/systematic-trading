package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class FakeExecutionJobRepository implements ExecutionJobRepository {

    private final AtomicLong seq = new AtomicLong(1);
    private final Map<Long, ExecutionJob> store = new HashMap<>();

    @Override
    public ExecutionJob save(ExecutionJob job) {
        if (job.getId() == null) {
            long id = seq.getAndIncrement();
            ExecutionJob withId = ExecutionJob.rehydrate(
                    id,
                    job.getSignalDate(),
                    job.getExecuteAfter(),
                    job.getStatus(),
                    job.getOrders(),
                    job.getStartedAt(),
                    job.getCompletedAt());
            store.put(id, withId);
            return withId;
        }
        store.put(job.getId(), job);
        return job;
    }

    @Override
    public Optional<ExecutionJob> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<ExecutionJob> findBySignalDate(LocalDate signalDate) {
        return store.values().stream()
                .filter(j -> j.getSignalDate().equals(signalDate))
                .findFirst();
    }

    @Override
    public List<ExecutionJob> findAllBySignalDate(LocalDate signalDate) {
        return store.values().stream()
                .filter(j -> j.getSignalDate().equals(signalDate))
                .toList();
    }

    @Override
    public List<ExecutionJob> findAll() {
        return new java.util.ArrayList<>(store.values());
    }

    @Override
    public List<ExecutionJob> findRecent(int page, int size) {
        return store.values().stream()
                .sorted(jobComparator())
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @Override
    public long countAll() {
        return store.size();
    }

    @Override
    public List<OrderWithJob> findOrdersByStatus(ExecutionOrderStatus status, int page, int size) {
        return store.values().stream()
                .flatMap(job -> job.getOrders().stream()
                        .filter(order -> order.getStatus() == status)
                        .map(order -> new OrderWithJob(job, order)))
                .sorted(Comparator
                        .comparing((OrderWithJob target) -> target.job().getSignalDate()).reversed()
                        .thenComparing(target -> target.job().getExecuteAfter(), Comparator.reverseOrder())
                        .thenComparing(target -> target.job().getId(), Comparator.reverseOrder())
                        .thenComparing(target -> target.order().getId(), Comparator.reverseOrder()))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @Override
    public long countOrdersByStatus(ExecutionOrderStatus status) {
        return store.values().stream()
                .flatMap(job -> job.getOrders().stream())
                .filter(order -> order.getStatus() == status)
                .count();
    }

    private Comparator<ExecutionJob> jobComparator() {
        return Comparator
                .comparing(ExecutionJob::getSignalDate).reversed()
                .thenComparing(ExecutionJob::getExecuteAfter, Comparator.reverseOrder())
                .thenComparing(ExecutionJob::getId, Comparator.reverseOrder());
    }
}
