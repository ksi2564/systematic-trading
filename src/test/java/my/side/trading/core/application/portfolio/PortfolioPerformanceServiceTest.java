package my.side.trading.core.application.portfolio;

import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.testutil.FakePortfolioSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioPerformanceServiceTest {

    @Test
    void 스냅샷이_없으면_사용불가를_반환한다() {
        PortfolioPerformanceService service = new PortfolioPerformanceService(new FakePortfolioSnapshotRepository());

        PortfolioPerformanceSummary summary = service.getSummary();

        assertThat(summary.dataAvailable()).isFalse();
        assertThat(summary.recentMonthlyPnl()).isEmpty();
    }

    @Test
    void 단일_스냅샷으로_요약을_계산한다() {
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
    void 여러_스냅샷으로_낙폭과_월간_손익을_계산한다() {
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

    @Test
    void 보고서에_최근_일별_스냅샷과_전체_월간_손익이_포함된다() {
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        repository.save(snapshot(LocalDate.of(2026, 1, 31), "1000.0000", "0.0000"));
        repository.save(snapshot(LocalDate.of(2026, 2, 28), "1100.0000", "0.0000"));
        repository.save(snapshot(LocalDate.of(2026, 3, 31), "1050.0000", "4.5455"));
        PortfolioPerformanceService service = new PortfolioPerformanceService(repository);

        PortfolioPerformanceReport report = service.getReport(2);

        assertThat(report.dailySnapshotLimit()).isEqualTo(2);
        assertThat(report.recentDailySnapshots()).hasSize(2);
        assertThat(report.recentDailySnapshots().getFirst().asOfDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(report.recentDailySnapshots().getLast().asOfDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(report.monthlyPnls()).hasSize(3);
        assertThat(report.monthlyPnls().getFirst().month()).isEqualTo("2026-01");
        assertThat(report.monthlyPnls().getLast().month()).isEqualTo("2026-03");
    }

    @Test
    void 일별_limit을_정규화하고_스냅샷이_없을때를_처리한다() {
        PortfolioPerformanceService service = new PortfolioPerformanceService(new FakePortfolioSnapshotRepository());

        PortfolioPerformanceReport report = service.getReport(0);

        assertThat(report.dailySnapshotLimit()).isEqualTo(60);
        assertThat(report.summary().dataAvailable()).isFalse();
        assertThat(report.recentDailySnapshots()).isEmpty();
        assertThat(report.monthlyPnls()).isEmpty();
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
