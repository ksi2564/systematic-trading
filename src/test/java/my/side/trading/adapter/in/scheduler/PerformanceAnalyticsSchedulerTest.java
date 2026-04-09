package my.side.trading.adapter.in.scheduler;

import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsService;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.time.MarketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PerformanceAnalyticsSchedulerTest {

    @Test
    void 정규장_상태면_성과분석을_집계한다() {
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        PortfolioPerformanceAnalyticsService analyticsService = mock(PortfolioPerformanceAnalyticsService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);
        LocalDate marketDate = LocalDate.of(2026, 4, 3);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(MarketStatus.REGULAR);

        PerformanceAnalyticsScheduler scheduler = new PerformanceAnalyticsScheduler(
                marketCalendarService,
                analyticsService,
                opsAlertPublisher
        );
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.captureDailyPerformanceAnalytics();

        verify(analyticsService).captureDailyAnalytics(marketDate);
        verifyNoInteractions(opsAlertPublisher);
    }

    @Test
    void 집계_실패면_운영알림을_발행한다() {
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        PortfolioPerformanceAnalyticsService analyticsService = mock(PortfolioPerformanceAnalyticsService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);
        LocalDate marketDate = LocalDate.of(2026, 4, 3);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(MarketStatus.REGULAR);
        doThrow(new IllegalStateException("boom")).when(analyticsService).captureDailyAnalytics(marketDate);

        PerformanceAnalyticsScheduler scheduler = new PerformanceAnalyticsScheduler(
                marketCalendarService,
                analyticsService,
                opsAlertPublisher
        );
        ReflectionTestUtils.setField(scheduler, "enabled", true);

        scheduler.captureDailyPerformanceAnalytics();

        verify(opsAlertPublisher).publish(argThat(alert ->
                alert.dedupeKey().equals("performance-analytics-failure:2026-04-03")));
    }

    @Test
    void 스케줄링이_비활성이면_아무_작업도_하지_않는다() {
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        PortfolioPerformanceAnalyticsService analyticsService = mock(PortfolioPerformanceAnalyticsService.class);
        OpsAlertPublisher opsAlertPublisher = mock(OpsAlertPublisher.class);

        PerformanceAnalyticsScheduler scheduler = new PerformanceAnalyticsScheduler(
                marketCalendarService,
                analyticsService,
                opsAlertPublisher
        );
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.captureDailyPerformanceAnalytics();

        verifyNoInteractions(marketCalendarService, analyticsService, opsAlertPublisher);
    }
}
