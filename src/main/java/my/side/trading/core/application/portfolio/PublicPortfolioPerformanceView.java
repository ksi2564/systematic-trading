package my.side.trading.core.application.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PublicPortfolioPerformanceView(
        BigDecimal baseIndex,
        List<DailyIndexPoint> recentDailyIndexSeries,
        List<MonthlyReturn> monthlyReturnSeries
) {
    public record DailyIndexPoint(
            LocalDate asOfDate,
            BigDecimal index
    ) {
    }

    public record MonthlyReturn(
            String month,
            BigDecimal returnPct
    ) {
    }

    public static PublicPortfolioPerformanceView empty(BigDecimal baseIndex) {
        return new PublicPortfolioPerformanceView(baseIndex, List.of(), List.of());
    }
}
