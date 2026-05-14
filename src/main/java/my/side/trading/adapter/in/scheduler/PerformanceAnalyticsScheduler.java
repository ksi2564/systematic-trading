package my.side.trading.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceAnalyticsService;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceAnalyticsScheduler {

    static final String MARKET_TIME_ZONE = "America/New_York";
    static final String PERFORMANCE_ANALYTICS_CRON = "0 30 16 * * MON-FRI";

    private final MarketCalendarService marketCalendarService;
    private final PortfolioPerformanceAnalyticsService portfolioPerformanceAnalyticsService;
    private final OpsAlertPublisher opsAlertPublisher;

    @Value("${trading.scheduling.enabled:false}")
    private boolean enabled;

    // 미국 동부시간 기준 EOD 직후 후속 배치로 실행한다.
    @Scheduled(cron = PERFORMANCE_ANALYTICS_CRON, zone = MARKET_TIME_ZONE)
    public void captureDailyPerformanceAnalytics() {
        if (!enabled) {
            return;
        }

        LocalDate marketDate = marketCalendarService.currentMarketDate();
        var marketStatus = marketCalendarService.getMarketStatus(marketDate);
        if (!marketStatus.allowsScheduledEod()) {
            log.info("[SCHED] 시장 캘린더 기준으로 성과 분석 수집을 건너뜁니다 | marketDate={}, marketStatus={}",
                    marketDate,
                    marketStatus);
            return;
        }

        try {
            portfolioPerformanceAnalyticsService.captureDailyAnalytics(marketDate);
        } catch (Exception e) {
            log.error("성과 분석 수집에 실패했습니다: asOfDate={}, reason={}",
                    marketDate,
                    SensitiveDataSanitizer.sanitizeThrowable(e));
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.PERFORMANCE_DATA_MISSING,
                    OpsAlertSeverity.ERROR,
                    "performance-analytics-failure:" + marketDate,
                    "Performance analytics capture failed",
                    Map.of(
                            "asOfDate", marketDate.toString(),
                            "source", "PerformanceAnalyticsScheduler",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage() == null ? "-" : SensitiveDataSanitizer.sanitize(e.getMessage()))));
        }
    }
}
