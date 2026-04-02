package my.side.trading.testutil;

import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.StrategyStateRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class FakeStrategyStateRepository implements StrategyStateRepository {

    private final List<StrategyState> states = new ArrayList<>();

    public FakeStrategyStateRepository(StrategyState initial) {
        if (initial != null) {
            this.states.add(initial);
        }
    }

    public void setLatest(StrategyState latest) {
        if (latest == null) {
            states.clear();
            return;
        }
        states.removeIf(state -> state.asOfDate().equals(latest.asOfDate()));
        states.add(latest);
    }

    @Override
    public Optional<StrategyState> findLatestState() {
        return states.stream()
                .max(Comparator.comparing(StrategyState::asOfDate));
    }

    @Override
    public Optional<StrategyState> findPreviousState(LocalDate asOfDate) {
        return states.stream()
                .filter(state -> state.asOfDate().isBefore(asOfDate))
                .max(Comparator.comparing(StrategyState::asOfDate));
    }

    @Override
    public StrategyState save(StrategyState state) {
        setLatest(state);
        return state;
    }
}
