package my.side.trading.core.domain.time;

import java.time.LocalDate;

public interface MarketStatusReader {

    MarketStatus getMarketStatus(LocalDate marketDate);
}
