package my.side.trading.core.application.portfolio;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import my.side.trading.core.infrastructure.config.TradingPerformanceProps;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PerformanceAnalyticsBackfillService {

    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final PortfolioPerformanceAnalyticsService portfolioPerformanceAnalyticsService;
    private final TradingPerformanceProps performanceProps;

    public RebuildResult rebuild(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }

        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findAllOrderByAsOfDateAsc().stream()
                .filter(snapshot -> !snapshot.asOfDate().isBefore(startDate) && !snapshot.asOfDate().isAfter(endDate))
                .toList();

        List<PerformanceAnalyticsSnapshot> rebuilt = snapshots.stream()
                .map(snapshot -> portfolioPerformanceAnalyticsService.captureDailyAnalytics(snapshot.asOfDate()))
                .toList();

        int actualReadyCount = (int) rebuilt.stream()
                .filter(PerformanceAnalyticsSnapshot::actualDataReady)
                .count();

        return new RebuildResult(startDate, endDate, rebuilt.size(), actualReadyCount);
    }

    public RebuildResult rebuildDefaultRange(LocalDate endDate) {
        int days = performanceProps.backfill().defaultDays();
        return rebuild(endDate.minusDays(days - 1L), endDate);
    }

    public record RebuildResult(
            LocalDate startDate,
            LocalDate endDate,
            int processedCount,
            int actualReadyCount
    ) {
    }
}
