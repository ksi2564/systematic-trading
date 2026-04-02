package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.ExecutionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExecutionJobJpaRepository extends JpaRepository<ExecutionJobEntity, Long> {
    Optional<ExecutionJobEntity> findBySignalDate(LocalDate signalDate);

    java.util.List<ExecutionJobEntity> findAllBySignalDate(LocalDate signalDate);
}
