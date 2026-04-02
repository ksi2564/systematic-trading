package my.side.trading.testutil;

import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FakeStrategyStateRepositoryTest {

    @Test
    void 이전_상태_조회는_현재일자보다_이전인_가장_최근_상태를_반환한다() {
        StrategyState first = state(LocalDate.of(2026, 3, 30), WeightSet.of(100, 0, 0));
        StrategyState second = state(LocalDate.of(2026, 3, 31), WeightSet.of(60, 30, 10));
        StrategyState latest = state(LocalDate.of(2026, 4, 1), WeightSet.of(40, 40, 20));

        FakeStrategyStateRepository repository = new FakeStrategyStateRepository(first);
        repository.save(second);
        repository.save(latest);

        assertThat(repository.findLatestState()).contains(latest);
        assertThat(repository.findPreviousState(latest.asOfDate())).contains(second);
    }

    private StrategyState state(LocalDate asOfDate, WeightSet weightSet) {
        return new StrategyState(
                asOfDate,
                new BigDecimal("500"),
                new BigDecimal("450"),
                new BigDecimal("10"),
                new BigDecimal("15"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.NORMAL,
                weightSet,
                true,
                1);
    }
}
