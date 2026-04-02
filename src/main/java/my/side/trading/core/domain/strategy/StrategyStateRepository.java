package my.side.trading.core.domain.strategy;

import java.time.LocalDate;
import java.util.Optional;

public interface StrategyStateRepository {
    Optional<StrategyState> findLatestState();
    Optional<StrategyState> findPreviousState(LocalDate asOfDate);
    StrategyState save(StrategyState state);
}
