package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionJobJpaRepository extends JpaRepository<ExecutionJobEntity, Long> {
}
