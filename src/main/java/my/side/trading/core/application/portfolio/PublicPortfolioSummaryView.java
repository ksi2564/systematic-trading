package my.side.trading.core.application.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PublicPortfolioSummaryView(
        boolean dataAvailable,
        LocalDate asOfDate,
        BigDecimal currentDrawdownPct,
        BigDecimal maxDrawdownPct,
        BigDecimal cumulativeReturnPct,
        HoldingWeights holdingWeights,
        List<MonthlyReturn> recentMonthlyReturnPcts
) {
    public record HoldingWeights(
            BigDecimal qqqPct,
            BigDecimal qldPct,
            BigDecimal tqqqPct,
            BigDecimal cashPct
    ) {
    }

    public record MonthlyReturn(
            String month,
            BigDecimal returnPct
    ) {
    }

    public static PublicPortfolioSummaryView unavailable() {
        return new PublicPortfolioSummaryView(
                false,
                null,
                null,
                null,
                null,
                new HoldingWeights(null, null, null, null),
                List.of()
        );
    }
}
