package my.side.trading.adapter.in.web.publicapi.dto;

import my.side.trading.core.application.portfolio.PublicPortfolioPerformanceView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PublicPortfolioPerformanceResponse(
        BigDecimal baseIndex,
        List<DailyIndexItem> recentDailyIndexSeries,
        List<MonthlyReturnItem> monthlyReturnSeries
) {
    public record DailyIndexItem(
            LocalDate asOfDate,
            BigDecimal index
    ) {
        public static DailyIndexItem from(PublicPortfolioPerformanceView.DailyIndexPoint point) {
            return new DailyIndexItem(point.asOfDate(), point.index());
        }
    }

    public record MonthlyReturnItem(
            String month,
            BigDecimal returnPct
    ) {
        public static MonthlyReturnItem from(PublicPortfolioPerformanceView.MonthlyReturn monthlyReturn) {
            return new MonthlyReturnItem(monthlyReturn.month(), monthlyReturn.returnPct());
        }
    }

    public static PublicPortfolioPerformanceResponse from(PublicPortfolioPerformanceView view) {
        return new PublicPortfolioPerformanceResponse(
                view.baseIndex(),
                view.recentDailyIndexSeries().stream()
                        .map(DailyIndexItem::from)
                        .toList(),
                view.monthlyReturnSeries().stream()
                        .map(MonthlyReturnItem::from)
                        .toList()
        );
    }
}
