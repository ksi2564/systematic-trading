package my.side.trading.core.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface BrokerDailyPerformanceReader {

    List<BrokerDailyPerformance> readDailyPerformances(LocalDate startDate, LocalDate endDate);

    record BrokerDailyPerformance(
            LocalDate date,
            BigDecimal realizedPnlUsd,
            BigDecimal brokerFeeUsd,
            BigDecimal taxUsd
    ) {
    }
}
