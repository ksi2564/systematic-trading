package my.side.trading.core.application.portfolio;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PublicPortfolioReadService {

    static final BigDecimal BASE_INDEX = new BigDecimal("100.0000");
    private static final int DEFAULT_DAILY_LIMIT = 180;
    private static final int MAX_DAILY_LIMIT = 366;
    private static final int RECENT_MONTH_LIMIT = 6;

    private final PortfolioSnapshotRepository portfolioSnapshotRepository;

    public PublicPortfolioSummaryView getSummary() {
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc();
        if (snapshots.isEmpty()) {
            return PublicPortfolioSummaryView.unavailable();
        }

        PortfolioSnapshot first = snapshots.getFirst();
        PortfolioSnapshot latest = snapshots.getLast();
        BigDecimal maxDrawdownPct = snapshots.stream()
                .map(PortfolioSnapshot::ddPercent)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));

        return new PublicPortfolioSummaryView(
                true,
                latest.asOfDate(),
                latest.ddPercent(),
                maxDrawdownPct,
                percentChange(first.totalValue(), latest.totalValue()),
                new PublicPortfolioSummaryView.HoldingWeights(
                        latest.wQqq(),
                        latest.wQld(),
                        latest.wTqqq(),
                        cashPct(latest)
                ),
                buildRecentMonthlyReturns(snapshots)
        );
    }

    public PublicPortfolioPerformanceView getPerformance(int dailyLimit) {
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc();
        if (snapshots.isEmpty()) {
            return PublicPortfolioPerformanceView.empty(BASE_INDEX);
        }

        List<PortfolioSnapshot> recentSnapshots = buildRecentDailySnapshots(snapshots, normalizeDailyLimit(dailyLimit));
        PortfolioSnapshot baseSnapshot = recentSnapshots.getFirst();

        return new PublicPortfolioPerformanceView(
                BASE_INDEX,
                recentSnapshots.stream()
                        .map(snapshot -> new PublicPortfolioPerformanceView.DailyIndexPoint(
                                snapshot.asOfDate(),
                                normalizedIndex(baseSnapshot.totalValue(), snapshot.totalValue())))
                        .toList(),
                buildMonthlyReturns(snapshots).stream()
                        .map(monthly -> new PublicPortfolioPerformanceView.MonthlyReturn(
                                monthly.month(),
                                monthly.returnPct()))
                        .toList()
        );
    }

    private List<PortfolioSnapshot> buildRecentDailySnapshots(List<PortfolioSnapshot> snapshots, int limit) {
        if (snapshots.size() <= limit) {
            return snapshots;
        }
        return snapshots.subList(snapshots.size() - limit, snapshots.size());
    }

    private List<PublicPortfolioSummaryView.MonthlyReturn> buildRecentMonthlyReturns(List<PortfolioSnapshot> snapshots) {
        List<PublicPortfolioSummaryView.MonthlyReturn> monthlyReturns = buildMonthlyReturns(snapshots);
        if (monthlyReturns.size() <= RECENT_MONTH_LIMIT) {
            return monthlyReturns;
        }
        return monthlyReturns.subList(monthlyReturns.size() - RECENT_MONTH_LIMIT, monthlyReturns.size());
    }

    private List<PublicPortfolioSummaryView.MonthlyReturn> buildMonthlyReturns(List<PortfolioSnapshot> snapshots) {
        Map<YearMonth, List<PortfolioSnapshot>> byMonth = new LinkedHashMap<>();
        for (PortfolioSnapshot snapshot : snapshots) {
            byMonth.computeIfAbsent(YearMonth.from(snapshot.asOfDate()), ignored -> new ArrayList<>())
                    .add(snapshot);
        }

        return byMonth.entrySet().stream()
                .map(entry -> {
                    PortfolioSnapshot first = entry.getValue().getFirst();
                    PortfolioSnapshot last = entry.getValue().getLast();
                    return new PublicPortfolioSummaryView.MonthlyReturn(
                            entry.getKey().toString(),
                            percentChange(first.totalValue(), last.totalValue()));
                })
                .toList();
    }

    private BigDecimal cashPct(PortfolioSnapshot snapshot) {
        if (snapshot.totalValue() == null || snapshot.totalValue().signum() <= 0 || snapshot.cash() == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        return snapshot.cash()
                .divide(snapshot.totalValue(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizedIndex(BigDecimal baseValue, BigDecimal currentValue) {
        if (baseValue == null || currentValue == null || baseValue.signum() <= 0) {
            return BASE_INDEX;
        }

        return currentValue
                .divide(baseValue, 6, RoundingMode.HALF_UP)
                .multiply(BASE_INDEX)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal percentChange(BigDecimal startValue, BigDecimal endValue) {
        if (startValue == null || endValue == null || startValue.signum() <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        return endValue.subtract(startValue)
                .divide(startValue, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private int normalizeDailyLimit(int dailyLimit) {
        return dailyLimit <= 0 ? DEFAULT_DAILY_LIMIT : Math.min(dailyLimit, MAX_DAILY_LIMIT);
    }
}
