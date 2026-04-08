package my.side.trading.adapter.in.web.publicapi.dto;

import my.side.trading.core.application.portfolio.PublicPortfolioSummaryView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PublicPortfolioSummaryResponse(
        boolean dataAvailable,
        LocalDate asOfDate,
        BigDecimal currentDrawdownPct,
        BigDecimal maxDrawdownPct,
        BigDecimal cumulativeReturnPct,
        HoldingWeights holdingWeights,
        List<MonthlyReturnItem> recentMonthlyReturnPcts
) {
    public record HoldingWeights(
            BigDecimal qqqPct,
            BigDecimal qldPct,
            BigDecimal tqqqPct,
            BigDecimal cashPct
    ) {
        public static HoldingWeights from(PublicPortfolioSummaryView.HoldingWeights weights) {
            return new HoldingWeights(
                    weights.qqqPct(),
                    weights.qldPct(),
                    weights.tqqqPct(),
                    weights.cashPct()
            );
        }
    }

    public record MonthlyReturnItem(
            String month,
            BigDecimal returnPct
    ) {
        public static MonthlyReturnItem from(PublicPortfolioSummaryView.MonthlyReturn monthlyReturn) {
            return new MonthlyReturnItem(monthlyReturn.month(), monthlyReturn.returnPct());
        }
    }

    public static PublicPortfolioSummaryResponse from(PublicPortfolioSummaryView view) {
        return new PublicPortfolioSummaryResponse(
                view.dataAvailable(),
                view.asOfDate(),
                view.currentDrawdownPct(),
                view.maxDrawdownPct(),
                view.cumulativeReturnPct(),
                HoldingWeights.from(view.holdingWeights()),
                view.recentMonthlyReturnPcts().stream()
                        .map(MonthlyReturnItem::from)
                        .toList()
        );
    }
}
