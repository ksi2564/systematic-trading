package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.StrategyStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface StrategyStateJpaRepository extends JpaRepository<StrategyStateEntity, Long> {

    Optional<StrategyStateEntity> findTopByOrderByAsOfDateDesc();

    Optional<StrategyStateEntity> findFirstByAsOfDateLessThanOrderByAsOfDateDesc(LocalDate asOfDate);
}
