package my.side.trading.core.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public interface FxRateReader {

    Map<LocalDate, BigDecimal> readUsdKrwRates(LocalDate startDate, LocalDate endDate);
}
