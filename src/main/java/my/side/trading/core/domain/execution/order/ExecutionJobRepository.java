package my.side.trading.core.domain.execution.order;

import java.util.Optional;

public interface ExecutionJobRepository {
    ExecutionJob save(ExecutionJob job);
    Optional<ExecutionJob> findById(Long id);
}
