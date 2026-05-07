package my.side.trading.core.domain.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

public record MarketDate(LocalDate value) {
    public static final ZoneId NY = ZoneId.of("America/New_York");

    public static MarketDate todayNy(Clock clock) {
        return new MarketDate(LocalDate.now(clock.withZone(NY)));
    }
}
