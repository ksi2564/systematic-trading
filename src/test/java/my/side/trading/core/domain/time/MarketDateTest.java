package my.side.trading.core.domain.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDateTest {

    @Test
    void todayNy는_주입된_clock의_뉴욕_일자를_사용한다() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-08T01:00:00Z"), ZoneOffset.UTC);

        MarketDate marketDate = MarketDate.todayNy(clock);

        assertThat(marketDate.value()).isEqualTo(LocalDate.of(2026, 5, 7));
    }
}
