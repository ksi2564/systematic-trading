package my.side.trading.adapter.in.scheduler;

import my.side.trading.adapter.out.kis.client.KisOverseasQuotedPriceService;
import my.side.trading.adapter.out.kis.dto.QuotedPriceResponse;
import my.side.trading.adapter.out.yahoo.YahooVixService;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSnapshotService;
import my.side.trading.core.application.strategy.StrategyStateEodService;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.domain.time.MarketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.Mockito.*;

class StrategyEodSchedulerTest {

    @Test
    void 데이터_미확정일이면_스케줄_EOD를_건너뛴다() {
        KisOverseasQuotedPriceService quotedPriceService = mock(KisOverseasQuotedPriceService.class);
        StrategyStateEodService eodService = mock(StrategyStateEodService.class);
        PortfolioPerformanceSnapshotService portfolioPerformanceSnapshotService = mock(PortfolioPerformanceSnapshotService.class);
        YahooVixService yahooVixService = mock(YahooVixService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);
        LocalDate marketDate = LocalDate.of(2026, 4, 2);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(MarketStatus.DATA_UNCERTAIN);

        StrategyEodScheduler scheduler = new StrategyEodScheduler(
                quotedPriceService,
                eodService,
                portfolioPerformanceSnapshotService,
                yahooVixService,
                marketCalendarService,
                opsAlertPublisher);
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.runScheduledEod();

        verifyNoInteractions(quotedPriceService, eodService, portfolioPerformanceSnapshotService, yahooVixService);
        verify(opsAlertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.DATA_UNCERTAIN));
    }

    @Test
    void 조기폐장일에도_스케줄_EOD는_실행한다() {
        KisOverseasQuotedPriceService quotedPriceService = mock(KisOverseasQuotedPriceService.class);
        StrategyStateEodService eodService = mock(StrategyStateEodService.class);
        PortfolioPerformanceSnapshotService portfolioPerformanceSnapshotService = mock(PortfolioPerformanceSnapshotService.class);
        YahooVixService yahooVixService = mock(YahooVixService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);
        LocalDate marketDate = LocalDate.of(2026, 11, 27);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(MarketStatus.EARLY_CLOSE);
        when(quotedPriceService.getQuotedPrice("QQQ")).thenReturn(sampleQuotedPriceResponse("499.12"));
        when(yahooVixService.getVixPrice()).thenReturn(Optional.of(new BigDecimal("22.34")));
        when(yahooVixService.getQqq200Ma()).thenReturn(Optional.of(new BigDecimal("470.11")));

        StrategyEodScheduler scheduler = new StrategyEodScheduler(
                quotedPriceService,
                eodService,
                portfolioPerformanceSnapshotService,
                yahooVixService,
                marketCalendarService,
                opsAlertPublisher);
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.runScheduledEod();

        verify(eodService).runEod(marketDate, new BigDecimal("499.12"));
        verify(portfolioPerformanceSnapshotService).captureDailySnapshot(marketDate);
        verifyNoInteractions(opsAlertPublisher);
    }

    @Test
    void EOD_실패시_알림을_발행한다() {
        KisOverseasQuotedPriceService quotedPriceService = mock(KisOverseasQuotedPriceService.class);
        StrategyStateEodService eodService = mock(StrategyStateEodService.class);
        PortfolioPerformanceSnapshotService portfolioPerformanceSnapshotService = mock(PortfolioPerformanceSnapshotService.class);
        YahooVixService yahooVixService = mock(YahooVixService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);
        LocalDate marketDate = LocalDate.of(2026, 4, 2);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(quotedPriceService.getQuotedPrice("QQQ")).thenReturn(sampleQuotedPriceResponse("499.12"));
        doThrow(new IllegalStateException("boom")).when(eodService).runEod(marketDate, new BigDecimal("499.12"));

        StrategyEodScheduler scheduler = new StrategyEodScheduler(
                quotedPriceService,
                eodService,
                portfolioPerformanceSnapshotService,
                yahooVixService,
                marketCalendarService,
                opsAlertPublisher);

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::runManualEod)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        verify(opsAlertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.EOD_FAILURE));
    }

    @Test
    void 성과스냅샷_저장실패는_EOD를_실패시키지_않는다() {
        KisOverseasQuotedPriceService quotedPriceService = mock(KisOverseasQuotedPriceService.class);
        StrategyStateEodService eodService = mock(StrategyStateEodService.class);
        PortfolioPerformanceSnapshotService portfolioPerformanceSnapshotService = mock(PortfolioPerformanceSnapshotService.class);
        YahooVixService yahooVixService = mock(YahooVixService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);
        LocalDate marketDate = LocalDate.of(2026, 4, 2);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(quotedPriceService.getQuotedPrice("QQQ")).thenReturn(sampleQuotedPriceResponse("499.12"));
        doThrow(new IllegalStateException("snapshot-boom"))
                .when(portfolioPerformanceSnapshotService).captureDailySnapshot(marketDate);

        StrategyEodScheduler scheduler = new StrategyEodScheduler(
                quotedPriceService,
                eodService,
                portfolioPerformanceSnapshotService,
                yahooVixService,
                marketCalendarService,
                opsAlertPublisher);

        scheduler.runManualEod();

        verify(eodService).runEod(marketDate, new BigDecimal("499.12"));
        verify(opsAlertPublisher).publish(argThat(alert -> alert.type() == OpsAlertType.PERFORMANCE_SNAPSHOT_FAILURE));
    }

    private QuotedPriceResponse sampleQuotedPriceResponse(String prevClosePrice) {
        return new QuotedPriceResponse(
                "0",
                "0",
                "ok",
                new QuotedPriceResponse.Item(
                        "QQQ",
                        "2",
                        prevClosePrice,
                        "0",
                        prevClosePrice,
                        "0",
                        "0",
                        "0",
                        "0",
                        "0",
                        "Y"));
    }
}
