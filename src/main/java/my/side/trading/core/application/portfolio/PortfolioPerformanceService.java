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
public class PortfolioPerformanceService {

    private static final int RECENT_MONTH_LIMIT = 6;
    private static final int DEFAULT_DAILY_LIMIT = 60;
    private static final int MAX_DAILY_LIMIT = 366;

    private final PortfolioSnapshotRepository portfolioSnapshotRepository;

    public PortfolioPerformanceSummary getSummary() {
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc();
        if (snapshots.isEmpty()) {
            return PortfolioPerformanceSummary.unavailable("No portfolio snapshots available");
        }

        PortfolioSnapshot first = snapshots.getFirst();
        PortfolioSnapshot latest = snapshots.getLast();
        BigDecimal peakNav = snapshots.stream()
                .map(PortfolioSnapshot::totalValue)
                .max(BigDecimal::compareTo)
                .orElse(latest.totalValue());
        BigDecimal maxDrawdownPct = snapshots.stream()
                .map(PortfolioSnapshot::ddPercent)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        BigDecimal cumulativePnlAmount = latest.totalValue().subtract(first.totalValue())
                .setScale(4, RoundingMode.HALF_UP);

        return new PortfolioPerformanceSummary(
                true,
                null,
                latest.asOfDate(),
                latest.totalValue(),
                peakNav,
                latest.ddPercent(),
                maxDrawdownPct,
                cumulativePnlAmount,
                percentChange(first.totalValue(), latest.totalValue()),
                buildRecentMonthlyPnl(snapshots)
        );
    }

    public PortfolioPerformanceReport getReport(int dailyLimit) {
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc();
        int normalizedDailyLimit = normalizeDailyLimit(dailyLimit);

        if (snapshots.isEmpty()) {
            return new PortfolioPerformanceReport(
                    PortfolioPerformanceSummary.unavailable("No portfolio snapshots available"),
                    normalizedDailyLimit,
                    List.of(),
                    List.of()
            );
        }

        return new PortfolioPerformanceReport(
                buildSummary(snapshots),
                normalizedDailyLimit,
                buildRecentDailySnapshots(snapshots, normalizedDailyLimit),
                buildMonthlyPnl(snapshots)
        );
    }

    private PortfolioPerformanceSummary buildSummary(List<PortfolioSnapshot> snapshots) {
        PortfolioSnapshot first = snapshots.getFirst();
        PortfolioSnapshot latest = snapshots.getLast();
        BigDecimal peakNav = snapshots.stream()
                .map(PortfolioSnapshot::totalValue)
                .max(BigDecimal::compareTo)
                .orElse(latest.totalValue());
        BigDecimal maxDrawdownPct = snapshots.stream()
                .map(PortfolioSnapshot::ddPercent)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        BigDecimal cumulativePnlAmount = latest.totalValue().subtract(first.totalValue())
                .setScale(4, RoundingMode.HALF_UP);

        return new PortfolioPerformanceSummary(
                true,
                null,
                latest.asOfDate(),
                latest.totalValue(),
                peakNav,
                latest.ddPercent(),
                maxDrawdownPct,
                cumulativePnlAmount,
                percentChange(first.totalValue(), latest.totalValue()),
                buildRecentMonthlyPnl(snapshots)
        );
    }

    private List<PortfolioPerformanceSummary.MonthlyPnl> buildRecentMonthlyPnl(List<PortfolioSnapshot> snapshots) {
        List<PortfolioPerformanceSummary.MonthlyPnl> monthlyPnls = buildMonthlyPnl(snapshots);
        if (monthlyPnls.size() <= RECENT_MONTH_LIMIT) {
            return monthlyPnls;
        }
        return monthlyPnls.subList(monthlyPnls.size() - RECENT_MONTH_LIMIT, monthlyPnls.size());
    }

    private List<PortfolioPerformanceSummary.MonthlyPnl> buildMonthlyPnl(List<PortfolioSnapshot> snapshots) {
        Map<YearMonth, List<PortfolioSnapshot>> byMonth = new LinkedHashMap<>();
        for (PortfolioSnapshot snapshot : snapshots) {
            byMonth.computeIfAbsent(YearMonth.from(snapshot.asOfDate()), ignored -> new ArrayList<>())
                    .add(snapshot);
        }

        return byMonth.entrySet().stream()
                .map(entry -> toMonthlyPnl(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<PortfolioSnapshot> buildRecentDailySnapshots(List<PortfolioSnapshot> snapshots, int limit) {
        if (snapshots.size() <= limit) {
            return snapshots;
        }
        return snapshots.subList(snapshots.size() - limit, snapshots.size());
    }

    private int normalizeDailyLimit(int dailyLimit) {
        return dailyLimit <= 0 ? DEFAULT_DAILY_LIMIT : Math.min(dailyLimit, MAX_DAILY_LIMIT);
    }

    private PortfolioPerformanceSummary.MonthlyPnl toMonthlyPnl(
            YearMonth month,
            List<PortfolioSnapshot> snapshots
    ) {
        PortfolioSnapshot first = snapshots.getFirst();
        PortfolioSnapshot last = snapshots.getLast();
        BigDecimal pnlAmount = last.totalValue().subtract(first.totalValue())
                .setScale(4, RoundingMode.HALF_UP);

        return new PortfolioPerformanceSummary.MonthlyPnl(
                month.toString(),
                first.totalValue(),
                last.totalValue(),
                pnlAmount,
                percentChange(first.totalValue(), last.totalValue())
        );
    }

    private BigDecimal percentChange(BigDecimal startValue, BigDecimal endValue) {
        if (startValue == null || endValue == null || startValue.signum() == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        return endValue.subtract(startValue)
                .divide(startValue, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
