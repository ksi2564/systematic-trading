package my.side.trading.core.domain.time;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record MarketDate(LocalDate value) {
    public static final ZoneId NY = ZoneId.of("America/New_York");

    public static MarketDate todayNy() {
        return new MarketDate(ZonedDateTime.now(NY).toLocalDate());
    }
}
