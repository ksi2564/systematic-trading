package my.side.trading.core.application.operation;

import my.side.trading.core.domain.time.MarketStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OperationsKpiSnapshot(
        LocalDate marketDate,
        MarketStatus marketStatus,
        LocalDate latestStrategyStateDate,
        boolean latestEodSuccess,
        int duplicateSignalJobCount,
        int unresolvedOrderCount,
        int rejectedOrderCount,
        int attemptedOrderCount,
        BigDecimal orderFailureRatePct,
        boolean breached,
        List<OperationsKpiBreach> breaches
) {
}
