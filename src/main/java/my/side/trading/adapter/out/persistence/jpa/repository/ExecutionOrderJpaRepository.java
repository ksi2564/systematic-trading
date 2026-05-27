package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionOrderEntity;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionOrderJpaRepository extends JpaRepository<ExecutionOrderEntity, Long> {

    List<ExecutionOrderEntity> findAllByJobId(Long jobId);

    @EntityGraph(attributePaths = "job")
    List<ExecutionOrderEntity> findByStatus(ExecutionOrderStatus status, Pageable pageable);

    long countByStatus(ExecutionOrderStatus status);
}
