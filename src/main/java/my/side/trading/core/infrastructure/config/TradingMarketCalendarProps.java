package my.side.trading.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@ConfigurationProperties(prefix = "trading.market-calendar")
public record TradingMarketCalendarProps(
        String marketZoneId,
        List<LocalDate> holidays,
        List<LocalDate> earlyCloses,
        List<LocalDate> dataUncertainDates
) {
    public TradingMarketCalendarProps {
        marketZoneId = marketZoneId == null || marketZoneId.isBlank()
                ? "America/New_York"
                : marketZoneId;
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
        earlyCloses = earlyCloses == null ? List.of() : List.copyOf(earlyCloses);
        dataUncertainDates = dataUncertainDates == null ? List.of() : List.copyOf(dataUncertainDates);
    }

    public ZoneId marketZone() {
        return ZoneId.of(marketZoneId);
    }
}
