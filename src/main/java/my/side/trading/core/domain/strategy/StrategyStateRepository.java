package my.side.trading.core.domain.strategy;

import java.util.Optional;

public interface StrategyStateRepository {
    Optional<StrategyState> findLatestState();
    StrategyState save(StrategyState state);
}
