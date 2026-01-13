package my.side.trading.core.domain.execution.order;

import java.time.LocalDate;
import java.util.Optional;

public interface ExecutionJobRepository {
    ExecutionJob save(ExecutionJob job);

    Optional<ExecutionJob> findById(Long id);

    Optional<ExecutionJob> findBySignalDate(LocalDate signalDate);
}
