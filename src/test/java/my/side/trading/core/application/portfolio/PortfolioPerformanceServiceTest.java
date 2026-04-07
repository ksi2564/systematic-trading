package my.side.trading.core.application.portfolio;

import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.testutil.FakePortfolioSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioPerformanceServiceTest {

    @Test
    void returnsUnavailableWhenNoSnapshotsExist() {
        PortfolioPerformanceService service = new PortfolioPerformanceService(new FakePortfolioSnapshotRepository());

        PortfolioPerformanceSummary summary = service.getSummary();

        assertThat(summary.dataAvailable()).isFalse();
        assertThat(summary.recentMonthlyPnl()).isEmpty();
    }

    @Test
    void calculatesSummaryFromSingleSnapshot() {
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        repository.save(snapshot(LocalDate.of(2026, 4, 7), "1000.0000", "0.0000"));
        PortfolioPerformanceService service = new PortfolioPerformanceService(repository);

        PortfolioPerformanceSummary summary = service.getSummary();

        assertThat(summary.dataAvailable()).isTrue();
        assertThat(summary.latestNav()).isEqualByComparingTo("1000.0000");
        assertThat(summary.currentDrawdownPct()).isEqualByComparingTo("0.0000");
        assertThat(summary.maxDrawdownPct()).isEqualByComparingTo("0.0000");
        assertThat(summary.cumulativePnlAmount()).isEqualByComparingTo("0.0000");
        assertThat(summary.cumulativePnlPct()).isEqualByComparingTo("0.0000");
        assertThat(summary.recentMonthlyPnl()).hasSize(1);
    }

    @Test
    void calculatesDrawdownAndMonthlyPnlAcrossSnapshots() {
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        repository.save(snapshot(LocalDate.of(2026, 3, 28), "1000.0000", "0.0000"));
        repository.save(snapshot(LocalDate.of(2026, 3, 31), "1100.0000", "0.0000"));
        repository.save(snapshot(LocalDate.of(2026, 4, 1), "1200.0000", "0.0000"));
        repository.save(snapshot(LocalDate.of(2026, 4, 7), "900.0000", "25.0000"));
        PortfolioPerformanceService service = new PortfolioPerformanceService(repository);

        PortfolioPerformanceSummary summary = service.getSummary();

        assertThat(summary.peakNav()).isEqualByComparingTo("1200.0000");
        assertThat(summary.currentDrawdownPct()).isEqualByComparingTo("25.0000");
        assertThat(summary.maxDrawdownPct()).isEqualByComparingTo("25.0000");
        assertThat(summary.cumulativePnlAmount()).isEqualByComparingTo("-100.0000");
        assertThat(summary.cumulativePnlPct()).isEqualByComparingTo("-10.0000");
        assertThat(summary.recentMonthlyPnl()).hasSize(2);
        assertThat(summary.recentMonthlyPnl().getFirst().month()).isEqualTo("2026-03");
        assertThat(summary.recentMonthlyPnl().getFirst().pnlAmount()).isEqualByComparingTo("100.0000");
        assertThat(summary.recentMonthlyPnl().getLast().month()).isEqualTo("2026-04");
        assertThat(summary.recentMonthlyPnl().getLast().pnlAmount()).isEqualByComparingTo("-300.0000");
    }

    private PortfolioSnapshot snapshot(LocalDate asOfDate, String totalValue, String ddPercent) {
        return new PortfolioSnapshot(
                asOfDate,
                new BigDecimal(totalValue),
                new BigDecimal(totalValue),
                BigDecimal.ZERO.setScale(4),
                BigDecimal.ZERO.setScale(4),
                BigDecimal.ZERO.setScale(4),
                new BigDecimal(ddPercent)
        );
    }
}
