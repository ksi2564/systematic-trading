package my.side.trading.core.application.portfolio;

import my.side.trading.core.application.port.out.BrokerDailyPerformanceReader;
import my.side.trading.core.application.port.out.FxRateReader;
import my.side.trading.core.infrastructure.config.TradingPerformanceProps;
import my.side.trading.testutil.FakePerformanceAnalyticsSnapshotRepository;
import my.side.trading.testutil.FakePortfolioSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceAnalyticsBackfillServiceTest {

    @Test
    void 지정한_기간만_재집계한다() {
        FakePortfolioSnapshotRepository portfolioSnapshotRepository = new FakePortfolioSnapshotRepository();
        portfolioSnapshotRepository.save(snapshot(LocalDate.of(2026, 4, 1), "950.0000"));
        portfolioSnapshotRepository.save(snapshot(LocalDate.of(2026, 4, 2), "980.0000"));
        portfolioSnapshotRepository.save(snapshot(LocalDate.of(2026, 4, 3), "1000.0000"));

        FakePerformanceAnalyticsSnapshotRepository analyticsSnapshotRepository =
                new FakePerformanceAnalyticsSnapshotRepository();
        TradingPerformanceProps props = new TradingPerformanceProps(
                new TradingPerformanceProps.BackfillProps(365),
                new TradingPerformanceProps.HoldingCostProps(
                        new TradingPerformanceProps.HoldingCostSymbolProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                new TradingPerformanceProps.FxProps("USDKRW"));

        PortfolioPerformanceAnalyticsService analyticsService = new PortfolioPerformanceAnalyticsService(
                new PortfolioPerformanceService(portfolioSnapshotRepository),
                portfolioSnapshotRepository,
                analyticsSnapshotRepository,
                (startDate, endDate) -> List.of(
                        new BrokerDailyPerformanceReader.BrokerDailyPerformance(
                                LocalDate.of(2026, 4, 2),
                                new BigDecimal("10.0000"),
                                new BigDecimal("1.0000"),
                                new BigDecimal("0.5000"))
                ),
                (startDate, endDate) -> Map.of(
                        LocalDate.of(2026, 4, 2), new BigDecimal("1430.00000000"),
                        LocalDate.of(2026, 4, 3), new BigDecimal("1440.00000000")
                ),
                props
        );

        PerformanceAnalyticsBackfillService backfillService = new PerformanceAnalyticsBackfillService(
                portfolioSnapshotRepository,
                analyticsService,
                props
        );

        PerformanceAnalyticsBackfillService.RebuildResult result = backfillService.rebuild(
                LocalDate.of(2026, 4, 2),
                LocalDate.of(2026, 4, 3)
        );

        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.actualReadyCount()).isEqualTo(1);
        assertThat(analyticsSnapshotRepository.findAllOrderByAsOfDateAsc())
                .extracting(snapshot -> snapshot.asOfDate())
                .containsExactly(LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3));
    }

    private static my.side.trading.core.domain.portfolio.PortfolioSnapshot snapshot(LocalDate date, String totalValue) {
        return new my.side.trading.core.domain.portfolio.PortfolioSnapshot(
                date,
                new BigDecimal(totalValue),
                new BigDecimal("100.0000"),
                new BigDecimal("100.0000"),
                BigDecimal.ZERO.setScale(4),
                BigDecimal.ZERO.setScale(4),
                BigDecimal.ZERO.setScale(4)
        );
    }
}
