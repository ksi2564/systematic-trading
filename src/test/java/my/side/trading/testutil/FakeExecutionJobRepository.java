package my.side.trading.testutil;

import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;

import java.time.LocalDate;
import java.util.HashMap;
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
    public java.util.List<ExecutionJob> findAllBySignalDate(LocalDate signalDate) {
        return store.values().stream()
                .filter(j -> j.getSignalDate().equals(signalDate))
                .toList();
    }

    @Override
    public java.util.List<ExecutionJob> findAll() {
        return new java.util.ArrayList<>(store.values());
    }
}
