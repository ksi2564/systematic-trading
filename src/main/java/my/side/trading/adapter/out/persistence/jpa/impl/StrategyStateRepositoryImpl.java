package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.adapter.out.persistence.jpa.entity.StrategyStateEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.StrategyStateJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class StrategyStateRepositoryImpl implements StrategyStateRepository {

    private final StrategyStateJpaRepository jpaRepository;

    @Override
    public Optional<StrategyState> findLatestState() {
        return jpaRepository.findTopByOrderByAsOfDateDesc()
                .map(StrategyStateEntity::toDomain);
    }

    @Override
    public StrategyState save(StrategyState state) {
        StrategyStateEntity entity = StrategyStateEntity.from(state);
        StrategyStateEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }
}
