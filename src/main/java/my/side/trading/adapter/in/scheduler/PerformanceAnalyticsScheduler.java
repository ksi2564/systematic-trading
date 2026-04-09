package my.side.trading.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsService;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceAnalyticsScheduler {

    private final MarketCalendarService marketCalendarService;
    private final PortfolioPerformanceAnalyticsService portfolioPerformanceAnalyticsService;
    private final OpsAlertPublisher opsAlertPublisher;

    @Value("${trading.scheduling.enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "0 0 09 * * TUE-SAT", zone = "Asia/Seoul")
    public void captureDailyPerformanceAnalytics() {
        if (!enabled) {
            return;
        }

        LocalDate marketDate = marketCalendarService.currentMarketDate();
        var marketStatus = marketCalendarService.getMarketStatus(marketDate);
        if (!marketStatus.allowsScheduledEod()) {
            log.info("[SCHED] performance analytics skipped by market calendar | marketDate={}, marketStatus={}",
                    marketDate,
                    marketStatus);
            return;
        }

        try {
            portfolioPerformanceAnalyticsService.captureDailyAnalytics(marketDate);
        } catch (Exception e) {
            log.error("Performance analytics capture failed: asOfDate={}", marketDate, e);
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.PERFORMANCE_DATA_MISSING,
                    OpsAlertSeverity.ERROR,
                    "performance-analytics-failure:" + marketDate,
                    "Performance analytics capture failed",
                    Map.of(
                            "asOfDate", marketDate.toString(),
                            "source", "PerformanceAnalyticsScheduler",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage() == null ? "-" : e.getMessage())));
        }
    }
}
