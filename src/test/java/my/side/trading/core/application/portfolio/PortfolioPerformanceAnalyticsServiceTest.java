package my.side.trading.core.application.portfolio;

import my.side.trading.core.application.port.out.BrokerDailyPerformanceReader;
import my.side.trading.core.application.port.out.FxRateReader;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.infrastructure.config.TradingPerformanceProps;
import my.side.trading.testutil.FakePerformanceAnalyticsSnapshotRepository;
import my.side.trading.testutil.FakePortfolioSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioPerformanceAnalyticsServiceTest {

    @Test
    void 실손익과_원화환산성과를_일별_월별로_집계한다() {
        FakePortfolioSnapshotRepository snapshotRepository = new FakePortfolioSnapshotRepository();
        snapshotRepository.save(snapshot("2026-04-01", "1000.0000", "100.0000", "100.0000", "0", "0", "0"));
        snapshotRepository.save(snapshot("2026-04-02", "1100.0000", "110.0000", "90.0000", "10.0000", "0", "0"));

        PortfolioPerformanceAnalyticsService service = new PortfolioPerformanceAnalyticsService(
                new PortfolioPerformanceService(snapshotRepository),
                snapshotRepository,
                new FakePerformanceAnalyticsSnapshotRepository(),
                rangeReader(List.of(
                        broker("2026-04-01", "10.0000", "1.0000", "2.0000"),
                        broker("2026-04-02", "20.0000", "2.0000", "3.0000")
                )),
                rangeFxReader(Map.of(
                        LocalDate.of(2026, 4, 1), new BigDecimal("1400.00000000"),
                        LocalDate.of(2026, 4, 2), new BigDecimal("1410.00000000")
                )),
                performanceProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        );

        service.captureDailyAnalytics(LocalDate.of(2026, 4, 1));
        service.captureDailyAnalytics(LocalDate.of(2026, 4, 2));

        PortfolioPerformanceAnalyticsReport report = service.getReport(30);

        assertThat(report.summary().actualPerformanceUsd().netActualPnlAmount()).isEqualByComparingTo("22.0000");
        assertThat(report.summary().actualPerformanceKrw().netActualPnlAmount()).isEqualByComparingTo("30950.0000");
        assertThat(report.summary().costBreakdown().brokerFeeUsd()).isEqualByComparingTo("3.0000");
        assertThat(report.summary().costBreakdown().taxKrw()).isEqualByComparingTo("7030.0000");
        assertThat(report.monthlyActualAnalytics()).singleElement()
                .satisfies(month -> {
                    assertThat(month.netActualPnlUsd()).isEqualByComparingTo("22.0000");
                    assertThat(month.netActualPnlKrw()).isEqualByComparingTo("30950.0000");
                });
    }

    @Test
    void 환율이나_브로커데이터가_없으면_커버리지를_partial로_표시한다() {
        FakePortfolioSnapshotRepository snapshotRepository = new FakePortfolioSnapshotRepository();
        snapshotRepository.save(snapshot("2026-04-01", "1000.0000", "100.0000", "100.0000", "0", "0", "0"));
        snapshotRepository.save(snapshot("2026-04-02", "1100.0000", "110.0000", "90.0000", "10.0000", "0", "0"));
        FakePerformanceAnalyticsSnapshotRepository analyticsRepository = new FakePerformanceAnalyticsSnapshotRepository();

        PortfolioPerformanceAnalyticsService service = new PortfolioPerformanceAnalyticsService(
                new PortfolioPerformanceService(snapshotRepository),
                snapshotRepository,
                analyticsRepository,
                rangeReader(List.of(broker("2026-04-02", "20.0000", "2.0000", "3.0000"))),
                rangeFxReader(Map.of(LocalDate.of(2026, 4, 2), new BigDecimal("1410.00000000"))),
                performanceProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        );

        PerformanceAnalyticsSnapshot first = service.captureDailyAnalytics(LocalDate.of(2026, 4, 1));
        PerformanceAnalyticsSnapshot second = service.captureDailyAnalytics(LocalDate.of(2026, 4, 2));
        PortfolioPerformanceAnalyticsSummary summary = service.getSummary();

        assertThat(first.actualDataReady()).isFalse();
        assertThat(second.actualDataReady()).isTrue();
        assertThat(summary.analysisCoverage().status()).isEqualTo("PARTIAL");
        assertThat(summary.analysisCoverage().missingDates()).containsExactly(LocalDate.of(2026, 4, 1));
    }

    @Test
    void FX_API가_비어도_브로커_응답_환율로_actual_ready를_계산한다() {
        FakePortfolioSnapshotRepository snapshotRepository = new FakePortfolioSnapshotRepository();
        snapshotRepository.save(snapshot("2026-04-02", "1100.0000", "110.0000", "90.0000", "10.0000", "0", "0"));
        FakePerformanceAnalyticsSnapshotRepository analyticsRepository = new FakePerformanceAnalyticsSnapshotRepository();

        PortfolioPerformanceAnalyticsService service = new PortfolioPerformanceAnalyticsService(
                new PortfolioPerformanceService(snapshotRepository),
                snapshotRepository,
                analyticsRepository,
                rangeReader(List.of(broker("2026-04-02", "20.0000", "2.0000", "3.0000", "1410.00000000"))),
                rangeFxReader(Map.of()),
                performanceProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        );

        PerformanceAnalyticsSnapshot snapshot = service.captureDailyAnalytics(LocalDate.of(2026, 4, 2));

        assertThat(snapshot.actualDataReady()).isTrue();
        assertThat(snapshot.fxRate()).isEqualByComparingTo("1410.00000000");
        assertThat(snapshot.realizedPnlKrw()).isEqualByComparingTo("28200.0000");
    }

    @Test
    void 스냅샷평가환율과_실손익환율을_분리해서_계산한다() {
        FakePortfolioSnapshotRepository snapshotRepository = new FakePortfolioSnapshotRepository();
        snapshotRepository.save(snapshot("2026-04-02", "1100.0000", "110.0000", "90.0000", "10.0000", "0", "0"));
        FakePerformanceAnalyticsSnapshotRepository analyticsRepository = new FakePerformanceAnalyticsSnapshotRepository();

        PortfolioPerformanceAnalyticsService service = new PortfolioPerformanceAnalyticsService(
                new PortfolioPerformanceService(snapshotRepository),
                snapshotRepository,
                analyticsRepository,
                rangeReader(List.of(broker("2026-04-02", "20.0000", "2.0000", "3.0000", "1410.00000000"))),
                rangeFxReader(Map.of(LocalDate.of(2026, 4, 2), new BigDecimal("1400.00000000"))),
                performanceProps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        );

        PerformanceAnalyticsSnapshot snapshot = service.captureDailyAnalytics(LocalDate.of(2026, 4, 2));

        assertThat(snapshot.fxRate()).isEqualByComparingTo("1400.00000000");
        assertThat(snapshot.navKrw()).isEqualByComparingTo("1540000.0000");
        assertThat(snapshot.realizedPnlKrw()).isEqualByComparingTo("28200.0000");
        assertThat(snapshot.brokerFeeKrw()).isEqualByComparingTo("2820.0000");
        assertThat(snapshot.taxKrw()).isEqualByComparingTo("4230.0000");
    }

    @Test
    void 보유비용추정치는_순실성과와_분리된다() {
        FakePortfolioSnapshotRepository snapshotRepository = new FakePortfolioSnapshotRepository();
        snapshotRepository.save(snapshot("2026-04-01", "36500.0000", "100.0000", "100.0000", "0", "0", "0"));
        FakePerformanceAnalyticsSnapshotRepository analyticsRepository = new FakePerformanceAnalyticsSnapshotRepository();

        PortfolioPerformanceAnalyticsService service = new PortfolioPerformanceAnalyticsService(
                new PortfolioPerformanceService(snapshotRepository),
                snapshotRepository,
                analyticsRepository,
                rangeReader(List.of(broker("2026-04-01", "10.0000", "1.0000", "1.0000"))),
                rangeFxReader(Map.of(LocalDate.of(2026, 4, 1), new BigDecimal("1400.00000000"))),
                performanceProps(new BigDecimal("1.0"), BigDecimal.ZERO, BigDecimal.ZERO)
        );

        PerformanceAnalyticsSnapshot snapshot = service.captureDailyAnalytics(LocalDate.of(2026, 4, 1));
        PortfolioPerformanceAnalyticsSummary summary = service.getSummary();

        assertThat(snapshot.holdingCostConfigured()).isTrue();
        assertThat(snapshot.holdingCostEstimateUsd()).isEqualByComparingTo("1.0000");
        assertThat(summary.actualPerformanceUsd().netActualPnlAmount()).isEqualByComparingTo("8.0000");
        assertThat(summary.holdingCostEstimate().totalEstimateUsd()).isEqualByComparingTo("1.0000");
    }

    private BrokerDailyPerformanceReader rangeReader(List<BrokerDailyPerformanceReader.BrokerDailyPerformance> values) {
        return (startDate, endDate) -> values.stream()
                .filter(item -> !item.date().isBefore(startDate) && !item.date().isAfter(endDate))
                .toList();
    }

    private FxRateReader rangeFxReader(Map<LocalDate, BigDecimal> values) {
        return (startDate, endDate) -> values.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(startDate) && !entry.getKey().isAfter(endDate))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private TradingPerformanceProps performanceProps(BigDecimal qqq, BigDecimal qld, BigDecimal tqqq) {
        return new TradingPerformanceProps(
                new TradingPerformanceProps.BackfillProps(365),
                new TradingPerformanceProps.HoldingCostProps(new TradingPerformanceProps.HoldingCostSymbolProps(qqq, qld, tqqq))
        );
    }

    private BrokerDailyPerformanceReader.BrokerDailyPerformance broker(
            String date,
            String realizedPnlUsd,
            String brokerFeeUsd,
            String taxUsd
    ) {
        return broker(date, realizedPnlUsd, brokerFeeUsd, taxUsd, "0");
    }

    private BrokerDailyPerformanceReader.BrokerDailyPerformance broker(
            String date,
            String realizedPnlUsd,
            String brokerFeeUsd,
            String taxUsd,
            String fxRate
    ) {
        return new BrokerDailyPerformanceReader.BrokerDailyPerformance(
                LocalDate.parse(date),
                new BigDecimal(realizedPnlUsd),
                new BigDecimal(brokerFeeUsd),
                new BigDecimal(taxUsd),
                new BigDecimal(fxRate)
        );
    }

    private PortfolioSnapshot snapshot(
            String asOfDate,
            String totalValue,
            String cash,
            String wQqq,
            String wQld,
            String wTqqq,
            String ddPercent
    ) {
        return new PortfolioSnapshot(
                LocalDate.parse(asOfDate),
                new BigDecimal(totalValue),
                new BigDecimal(cash),
                new BigDecimal(wQqq),
                new BigDecimal(wQld),
                new BigDecimal(wTqqq),
                new BigDecimal(ddPercent)
        );
    }
}
