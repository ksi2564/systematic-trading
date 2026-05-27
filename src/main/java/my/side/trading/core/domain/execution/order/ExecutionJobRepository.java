package my.side.trading.core.domain.execution.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobRepository {
    ExecutionJob save(ExecutionJob job);

    Optional<ExecutionJob> findById(Long id);

    Optional<ExecutionJob> findBySignalDate(LocalDate signalDate);

    List<ExecutionJob> findAllBySignalDate(LocalDate signalDate);

    List<ExecutionJob> findAll();

    List<ExecutionJob> findRecent(int page, int size);

    long countAll();

    List<OrderWithJob> findOrdersByStatus(ExecutionOrderStatus status, int page, int size);

    long countOrdersByStatus(ExecutionOrderStatus status);

    record OrderWithJob(ExecutionJob job, ExecutionOrder order) {
    }
}
