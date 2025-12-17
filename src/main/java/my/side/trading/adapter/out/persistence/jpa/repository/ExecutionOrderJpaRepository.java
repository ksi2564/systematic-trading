package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionOrderJpaRepository extends JpaRepository<ExecutionOrderEntity, Long> {

    List<ExecutionOrderEntity> findAllByJobId(Long jobId);
}
