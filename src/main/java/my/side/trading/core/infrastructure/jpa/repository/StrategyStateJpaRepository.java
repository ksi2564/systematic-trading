package my.side.trading.core.infrastructure.jpa.repository;

import my.side.trading.core.infrastructure.jpa.entity.StrategyStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StrategyStateJpaRepository extends JpaRepository<StrategyStateEntity, Long> {

    Optional<StrategyStateEntity> findTopByOrderByAsOfDateDesc();
}
