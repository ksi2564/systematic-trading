package my.side.trading.testutil;

import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;

import java.util.Optional;

public final class FakeStrategyStateRepository implements StrategyStateRepository {

    private StrategyState latest;

    public FakeStrategyStateRepository(StrategyState initial) {
        this.latest = initial;
    }

    public void setLatest(StrategyState latest) {
        this.latest = latest;
    }

    @Override
    public Optional<StrategyState> findLatestState() {
        return Optional.ofNullable(latest);
    }

    @Override
    public StrategyState save(StrategyState state) {
        this.latest = state;
        return state;
    }
}
