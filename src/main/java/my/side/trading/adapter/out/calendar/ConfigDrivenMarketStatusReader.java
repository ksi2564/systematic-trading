package my.side.trading.adapter.out.calendar;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.time.MarketStatus;
import my.side.trading.core.domain.time.MarketStatusReader;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class ConfigDrivenMarketStatusReader implements MarketStatusReader {

    private final TradingMarketCalendarProps props;

    @Override
    public MarketStatus getMarketStatus(LocalDate marketDate) {
        if (props.dataUncertainDates().contains(marketDate)) {
            return MarketStatus.DATA_UNCERTAIN;
        }
        if (isWeekend(marketDate) || props.holidays().contains(marketDate)) {
            return MarketStatus.HOLIDAY;
        }
        if (props.earlyCloses().contains(marketDate)) {
            return MarketStatus.EARLY_CLOSE;
        }
        return MarketStatus.REGULAR;
    }

    private boolean isWeekend(LocalDate marketDate) {
        DayOfWeek dayOfWeek = marketDate.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
