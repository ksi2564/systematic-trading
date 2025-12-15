package my.side.trading.core.infrastructure.jpa.repository;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.infrastructure.jpa.entity.StrategyStateEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class StrategyStateRepository {

    private final StrategyStateJpaRepository jpaRepository;

    public Optional<StrategyState> findLatestState() {
        return jpaRepository.findTopByOrderByAsOfDateDesc()
                .map(StrategyStateEntity::toDomain);
    }

    public StrategyState save(StrategyState state) {
        StrategyStateEntity entity = StrategyStateEntity.from(state);
        StrategyStateEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }
}
