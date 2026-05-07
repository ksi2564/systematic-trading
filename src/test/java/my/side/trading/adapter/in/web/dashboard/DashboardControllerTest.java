package my.side.trading.adapter.in.web.dashboard;

import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardHistoryResponse;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardPerformanceResponse;
import my.side.trading.adapter.in.web.dashboard.dto.DashboardResponse;
import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.execution.ExecutionGuardSnapshot;
import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.application.operation.OperationsKpiSnapshot;
import my.side.trading.core.application.port.out.CurrentFxRateProvider;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsReport;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsSummary;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSummary;
import my.side.trading.core.application.portfolio.PortfolioService;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import my.side.trading.core.domain.execution.order.ExecutionOrder;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.ExecutionStatus;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeTransitionType;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import my.side.trading.core.domain.strategy.StrategyStateRepository;
import my.side.trading.core.domain.time.MarketStatus;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {

    @Test
    void 요약응답에_운영모드와_성과분석블록이_포함된다() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        StrategyStateRepository strategyStateRepository = mock(StrategyStateRepository.class);
        StrategyEodScheduler scheduler = mock(StrategyEodScheduler.class);
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        my.side.trading.core.application.operation.OperationsKpiService operationsKpiService =
                mock(my.side.trading.core.application.operation.OperationsKpiService.class);
        PortfolioPerformanceAnalyticsService analyticsService = mock(PortfolioPerformanceAnalyticsService.class);
        CurrentFxRateProvider currentFxRateProvider = mock(CurrentFxRateProvider.class);
        PortfolioSnapshotRepository portfolioSnapshotRepository = mock(PortfolioSnapshotRepository.class);
        OperatingModeService operatingModeService = mock(OperatingModeService.class);

        when(portfolioService.getCurrentPortfolio()).thenReturn(new Portfolio(BigDecimal.TEN, List.of()));
        when(strategyStateRepository.findLatestState()).thenReturn(Optional.empty());
        when(scheduler.getVix()).thenReturn(new BigDecimal("20.5"));
        when(scheduler.getQqq200Ma()).thenReturn(new BigDecimal("500.0"));
        when(jobRepository.findAll()).thenReturn(List.of(sampleJob()));
        when(executionGuard.snapshot()).thenReturn(new ExecutionGuardSnapshot(
                OperatingMode.MANUAL_LIVE,
                true,
                false,
                null,
                null,
                new ExecutionGuardSnapshot.AutoLiveGateSnapshot(5, true, true, true, false)));
        when(operationsKpiService.snapshot()).thenReturn(new OperationsKpiSnapshot(
                LocalDate.of(2026, 4, 3),
                MarketStatus.REGULAR,
                LocalDate.of(2026, 4, 2),
                true,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                false,
                List.of()));
        when(analyticsService.getSummary()).thenReturn(sampleAnalyticsSummary());
        when(currentFxRateProvider.getCurrentUsdKrwRate()).thenReturn(Optional.of(new BigDecimal("1435.5000")));
        when(operatingModeService.currentMode()).thenReturn(OperatingMode.MANUAL_LIVE);
        when(operatingModeService.recentHistory(5)).thenReturn(List.of(auditEvent()));

        DashboardController controller = new DashboardController(
                portfolioService,
                strategyStateRepository,
                scheduler,
                jobRepository,
                executionGuard,
                operationsKpiService,
                analyticsService,
                currentFxRateProvider,
                portfolioSnapshotRepository,
                operatingModeService,
                marketCalendarProps());

        DashboardResponse response = controller.getSummary().data();

        assertThat(response.operatingMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
        assertThat(response.performance().dataAvailable()).isTrue();
        assertThat(response.performance().latestNav()).isEqualByComparingTo("1000.0000");
        assertThat(response.performance().actualPerformanceUsd().netActualPnlAmount()).isEqualByComparingTo("12.0000");
        assertThat(response.performance().holdingCostEstimate().configured()).isTrue();
        assertThat(response.realtimePortfolioValuation().available()).isTrue();
        assertThat(response.realtimePortfolioValuation().totalValueUsd()).isEqualByComparingTo("10.0000");
        assertThat(response.realtimePortfolioValuation().fxRate()).isEqualByComparingTo("1435.5000");
        assertThat(response.realtimePortfolioValuation().totalValueKrw()).isEqualByComparingTo("14355.0000");
        assertThat(response.recentOperatingModeAudits()).hasSize(1);
    }

    @Test
    void 이력응답에_포트폴리오와_성과분석스냅샷이_최신순으로_포함된다() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        StrategyStateRepository strategyStateRepository = mock(StrategyStateRepository.class);
        StrategyEodScheduler scheduler = mock(StrategyEodScheduler.class);
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        my.side.trading.core.application.operation.OperationsKpiService operationsKpiService =
                mock(my.side.trading.core.application.operation.OperationsKpiService.class);
        PortfolioPerformanceAnalyticsService analyticsService = mock(PortfolioPerformanceAnalyticsService.class);
        CurrentFxRateProvider currentFxRateProvider = mock(CurrentFxRateProvider.class);
        PortfolioSnapshotRepository portfolioSnapshotRepository = mock(PortfolioSnapshotRepository.class);
        OperatingModeService operatingModeService = mock(OperatingModeService.class);

        when(jobRepository.findAll()).thenReturn(List.of(
                sampleJob(1L, LocalDate.of(2026, 4, 2), Instant.parse("2026-04-02T23:40:00Z")),
                sampleJob(2L, LocalDate.of(2026, 4, 3), Instant.parse("2026-04-03T23:45:00Z"))
        ));
        when(operatingModeService.recentHistory(2)).thenReturn(List.of(
                auditEvent(2L, Instant.parse("2026-04-03T01:00:00Z")),
                auditEvent(1L, Instant.parse("2026-04-02T01:00:00Z"))
        ));
        when(portfolioSnapshotRepository.findAllOrderByAsOfDateAsc()).thenReturn(List.of(
                portfolioSnapshot(LocalDate.of(2026, 4, 2), "980.0000"),
                portfolioSnapshot(LocalDate.of(2026, 4, 3), "1000.0000")
        ));
        when(analyticsService.getRecentSnapshots(2)).thenReturn(List.of(
                analyticsSnapshot(LocalDate.of(2026, 4, 2), false),
                analyticsSnapshot(LocalDate.of(2026, 4, 3), true)
        ));

        DashboardController controller = new DashboardController(
                portfolioService,
                strategyStateRepository,
                scheduler,
                jobRepository,
                executionGuard,
                operationsKpiService,
                analyticsService,
                currentFxRateProvider,
                portfolioSnapshotRepository,
                operatingModeService,
                marketCalendarProps());

        DashboardHistoryResponse response = controller.getHistory(2).data();

        assertThat(response.jobs()).hasSize(2);
        assertThat(response.displayTimeZones().operator()).isEqualTo("Asia/Seoul");
        assertThat(response.displayTimeZones().market()).isEqualTo("America/New_York");
        assertThat(response.jobs().getFirst().executeAfter()).isEqualTo(Instant.parse("2026-04-03T23:45:00Z"));
        assertThat(response.performanceSnapshots().getFirst().asOfDate()).isEqualTo(LocalDate.of(2026, 4, 3));
        assertThat(response.performanceAnalyticsSnapshots()).hasSize(2);
        assertThat(response.performanceAnalyticsSnapshots().getFirst().actualDataReady()).isTrue();
    }

    @Test
    void 성과응답에_실성과_시계열이_포함된다() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        StrategyStateRepository strategyStateRepository = mock(StrategyStateRepository.class);
        StrategyEodScheduler scheduler = mock(StrategyEodScheduler.class);
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        ExecutionGuard executionGuard = mock(ExecutionGuard.class);
        my.side.trading.core.application.operation.OperationsKpiService operationsKpiService =
                mock(my.side.trading.core.application.operation.OperationsKpiService.class);
        PortfolioPerformanceAnalyticsService analyticsService = mock(PortfolioPerformanceAnalyticsService.class);
        CurrentFxRateProvider currentFxRateProvider = mock(CurrentFxRateProvider.class);
        PortfolioSnapshotRepository portfolioSnapshotRepository = mock(PortfolioSnapshotRepository.class);
        OperatingModeService operatingModeService = mock(OperatingModeService.class);

        when(analyticsService.getReport(30)).thenReturn(new PortfolioPerformanceAnalyticsReport(
                sampleAnalyticsSummary(),
                30,
                List.of(
                        portfolioSnapshot(LocalDate.of(2026, 4, 7), "1080.0000"),
                        portfolioSnapshot(LocalDate.of(2026, 4, 8), "1100.0000")
                ),
                List.of(new PortfolioPerformanceSummary.MonthlyPnl(
                        "2026-04",
                        new BigDecimal("1000.0000"),
                        new BigDecimal("1100.0000"),
                        new BigDecimal("100.0000"),
                        new BigDecimal("10.0000"))),
                List.of(analyticsSnapshot(LocalDate.of(2026, 4, 8), true)),
                List.of(new PortfolioPerformanceAnalyticsReport.ActualMonthlyAnalytics(
                        "2026-04",
                        2,
                        1,
                        new BigDecimal("12.0000"),
                        new BigDecimal("17160.0000"),
                        new BigDecimal("15.0000"),
                        new BigDecimal("21450.0000"),
                        new BigDecimal("2.0000"),
                        new BigDecimal("2860.0000"),
                        new BigDecimal("1.0000"),
                        new BigDecimal("1430.0000"),
                        new BigDecimal("0.7500"),
                        new BigDecimal("1072.5000")
                ))
        ));

        DashboardController controller = new DashboardController(
                portfolioService,
                strategyStateRepository,
                scheduler,
                jobRepository,
                executionGuard,
                operationsKpiService,
                analyticsService,
                currentFxRateProvider,
                portfolioSnapshotRepository,
                operatingModeService,
                marketCalendarProps());

        DashboardPerformanceResponse response = controller.getPerformance(30).data();

        assertThat(response.summary().actualPerformanceKrw().netActualPnlAmount()).isEqualByComparingTo("17160.0000");
        assertThat(response.recentDailySnapshots()).hasSize(2);
        assertThat(response.recentDailyActualSnapshots()).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.actualDataReady()).isTrue());
        assertThat(response.monthlyActualAnalytics()).singleElement()
                .satisfies(month -> assertThat(month.netActualPnlUsd()).isEqualByComparingTo("12.0000"));
    }

    private PortfolioPerformanceAnalyticsSummary sampleAnalyticsSummary() {
        return new PortfolioPerformanceAnalyticsSummary(
                new PortfolioPerformanceSummary(
                        true,
                        null,
                        LocalDate.of(2026, 4, 3),
                        new BigDecimal("1000.0000"),
                        new BigDecimal("1200.0000"),
                        new BigDecimal("16.6667"),
                        new BigDecimal("25.0000"),
                        new BigDecimal("50.0000"),
                        new BigDecimal("5.0000"),
                        List.of(new PortfolioPerformanceSummary.MonthlyPnl(
                                "2026-04",
                                new BigDecimal("950.0000"),
                                new BigDecimal("1000.0000"),
                                new BigDecimal("50.0000"),
                                new BigDecimal("5.2632")))
                ),
                new PortfolioPerformanceAnalyticsSummary.ActualPerformanceSummary(
                        LocalDate.of(2026, 4, 3),
                        new BigDecimal("1000.0000"),
                        new BigDecimal("12.0000"),
                        new BigDecimal("1.2000"),
                        new BigDecimal("15.0000")
                ),
                new PortfolioPerformanceAnalyticsSummary.ActualPerformanceSummary(
                        LocalDate.of(2026, 4, 3),
                        new BigDecimal("1430000.0000"),
                        new BigDecimal("17160.0000"),
                        new BigDecimal("1.2000"),
                        new BigDecimal("21450.0000")
                ),
                new PortfolioPerformanceAnalyticsSummary.CostBreakdown(
                        new BigDecimal("2.0000"),
                        new BigDecimal("2860.0000"),
                        new BigDecimal("1.0000"),
                        new BigDecimal("1430.0000")
                ),
                new PortfolioPerformanceAnalyticsSummary.HoldingCostEstimate(
                        true,
                        new BigDecimal("0.7500"),
                        new BigDecimal("1072.5000")
                ),
                new PortfolioPerformanceAnalyticsSummary.AnalysisCoverage(
                        "PARTIAL",
                        LocalDate.of(2026, 4, 2),
                        LocalDate.of(2026, 4, 3),
                        2,
                        1,
                        List.of(LocalDate.of(2026, 4, 2))
                )
        );
    }

    private PortfolioSnapshot portfolioSnapshot(LocalDate date, String totalValue) {
        return new PortfolioSnapshot(
                date,
                new BigDecimal(totalValue),
                new BigDecimal("100.0000"),
                new BigDecimal("90.0000"),
                new BigDecimal("10.0000"),
                BigDecimal.ZERO.setScale(4),
                new BigDecimal("1.0000")
        );
    }

    private PerformanceAnalyticsSnapshot analyticsSnapshot(LocalDate date, boolean actualDataReady) {
        return new PerformanceAnalyticsSnapshot(
                date,
                new BigDecimal("1000.0000"),
                new BigDecimal("1430000.0000"),
                new BigDecimal("1430.00000000"),
                new BigDecimal("15.0000"),
                new BigDecimal("21450.0000"),
                new BigDecimal("2.0000"),
                new BigDecimal("2860.0000"),
                new BigDecimal("1.0000"),
                new BigDecimal("1430.0000"),
                actualDataReady,
                new BigDecimal("0.7500"),
                new BigDecimal("1072.5000"),
                true
        );
    }

    private OperatingModeAuditEvent auditEvent() {
        return auditEvent(1L, Instant.parse("2026-04-03T00:00:00Z"));
    }

    private OperatingModeAuditEvent auditEvent(Long id, Instant createdAt) {
        return new OperatingModeAuditEvent(
                id,
                OperatingMode.AUTO_LIVE,
                OperatingMode.MANUAL_LIVE,
                OperatingModeTransitionType.DEMOTION,
                OperatingModeTriggerSource.SYSTEM,
                "KPI_BREACH",
                "system",
                "Automatic demotion triggered by KPI_BREACH",
                null,
                null,
                createdAt);
    }

    private ExecutionJob sampleJob() {
        return sampleJob(1L, LocalDate.of(2026, 4, 3), Instant.parse("2026-04-03T23:45:00Z"));
    }

    private ExecutionJob sampleJob(Long id, LocalDate signalDate, Instant executeAfter) {
        return ExecutionJob.rehydrate(
                id,
                signalDate,
                executeAfter,
                ExecutionStatus.COMPLETED,
                List.of(ExecutionOrder.rehydrate(
                        1L,
                        "QQQ",
                        ExecutionOrderSide.BUY,
                        1,
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        ExecutionOrderStatus.ACCEPTED,
                        "ORD-001",
                        "accepted")),
                executeAfter.plusSeconds(60),
                executeAfter.plusSeconds(180));
    }

    private TradingMarketCalendarProps marketCalendarProps() {
        return new TradingMarketCalendarProps("America/New_York", List.of(), List.of(), List.of());
    }
}
