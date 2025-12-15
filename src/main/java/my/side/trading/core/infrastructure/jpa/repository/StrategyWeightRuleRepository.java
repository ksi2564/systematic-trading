package my.side.trading.core.infrastructure.jpa.repository;

import my.side.trading.core.infrastructure.jpa.entity.StrategyWeightRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface StrategyWeightRuleRepository extends JpaRepository<StrategyWeightRuleEntity, Long> {
    @Query("""
        SELECT r
        FROM StrategyWeightRuleEntity r
        WHERE r.ddFrom <= :dd
          AND r.ddTo > :dd
          AND r.version = :version
    """)
    Optional<StrategyWeightRuleEntity> findRule(
            @Param("dd") BigDecimal dd,
            @Param("version") int version
    );
}
