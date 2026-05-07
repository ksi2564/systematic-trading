package my.side.trading.core.application.market;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.time.MarketStatus;
import my.side.trading.core.domain.time.MarketStatusReader;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class MarketCalendarService {

    private final MarketStatusReader marketStatusReader;
    private final TradingMarketCalendarProps props;
    private final Clock clock;

    public LocalDate currentMarketDate() {
        return LocalDate.now(clock.withZone(props.marketZone()));
    }

    public MarketStatus getMarketStatus(LocalDate marketDate) {
        return marketStatusReader.getMarketStatus(marketDate);
    }
}
