package my.side.trading.core.application.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PortfolioPerformanceSummary(
        boolean dataAvailable,
        String missingReason,
        LocalDate asOfDate,
        BigDecimal latestNav,
        BigDecimal peakNav,
        BigDecimal currentDrawdownPct,
        BigDecimal maxDrawdownPct,
        BigDecimal cumulativePnlAmount,
        BigDecimal cumulativePnlPct,
        List<MonthlyPnl> recentMonthlyPnl
) {
    public record MonthlyPnl(
            String month,
            BigDecimal startNav,
            BigDecimal endNav,
            BigDecimal pnlAmount,
            BigDecimal pnlPct
    ) {
    }

    public static PortfolioPerformanceSummary unavailable(String missingReason) {
        return new PortfolioPerformanceSummary(
                false,
                missingReason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of()
        );
    }
}
