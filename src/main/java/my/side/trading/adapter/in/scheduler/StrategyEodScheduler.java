package my.side.trading.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasQuotedPriceService;
import my.side.trading.adapter.out.kis.dto.QuotedPriceResponse;
import my.side.trading.adapter.out.yahoo.YahooVixService;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PortfolioPerformanceSnapshotService;
import my.side.trading.core.application.strategy.StrategyStateEodService;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.domain.time.MarketStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEodScheduler {

    private final KisOverseasQuotedPriceService quotedPriceService;
    private final StrategyStateEodService eodService;
    private final PortfolioPerformanceSnapshotService portfolioPerformanceSnapshotService;
    private final YahooVixService yahooVixService;
    private final MarketCalendarService marketCalendarService;
    private final OpsAlertPublisher opsAlertPublisher;

    @Value("${trading.scheduling.enabled:false}")
    private boolean enabled;

    // KST 기준 미장 마감 이후 15분 여유
    // 정리 예정: 추후 계절시간을 반영해야 한다.
    @Scheduled(cron = "0 15 06 * * TUE-SAT", zone = "Asia/Seoul") // 06:15 KST
    public void runScheduledEod() {
        if (!enabled) {
            return;
        }
        LocalDate marketDate = marketCalendarService.currentMarketDate();
        var marketStatus = marketCalendarService.getMarketStatus(marketDate);
        if (!marketStatus.allowsScheduledEod()) {
            log.info("[SCHED] 시장 캘린더 기준으로 EOD를 건너뜁니다 | marketDate={}, marketStatus={}",
                    marketDate,
                    marketStatus);
            if (marketStatus == MarketStatus.DATA_UNCERTAIN) {
                opsAlertPublisher.publish(new OpsAlert(
                        OpsAlertType.DATA_UNCERTAIN,
                        OpsAlertSeverity.ERROR,
                        "data-uncertain:eod:" + marketDate,
                        "Scheduled EOD skipped because market data is uncertain",
                        Map.of(
                                "marketDate", marketDate.toString(),
                                "marketStatus", marketStatus.name(),
                                "source", "StrategyEodScheduler")));
            }
            return;
        }
        runEod(marketDate);
    }

    public void runManualEod() {
        runEod(marketCalendarService.currentMarketDate());
    }

    private void runEod(LocalDate asOfDate) {
        try {
            QuotedPriceResponse res = quotedPriceService.getQuotedPrice("QQQ");
            BigDecimal close = new BigDecimal(res.item().prevClosePrice());

            // VIX와 200MA를 조회한다. (서킷 브레이커 판단용)
            BigDecimal vix = yahooVixService.getVixPrice().orElse(null);
            BigDecimal qqqMa200 = yahooVixService.getQqq200Ma().orElse(null);

            log.info("서킷 브레이커 데이터: VIX={}, QQQ_200MA={}", vix, qqqMa200);

            eodService.runEod(asOfDate, close);
            capturePerformanceSnapshot(asOfDate);

            log.info("EOD를 갱신했습니다: asOfDate={}, qqqClose={}", asOfDate, close);
        } catch (Exception e) {
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.EOD_FAILURE,
                    OpsAlertSeverity.ERROR,
                    "eod-failure:" + asOfDate,
                    "EOD calculation failed",
                    Map.of(
                            "asOfDate", asOfDate.toString(),
                            "source", "StrategyEodScheduler",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage() == null ? "-" : e.getMessage())));
            throw e;
        }
    }

    private void capturePerformanceSnapshot(LocalDate asOfDate) {
        try {
            portfolioPerformanceSnapshotService.captureDailySnapshot(asOfDate);
        } catch (Exception e) {
            log.error("성과 스냅샷 저장에 실패했습니다: asOfDate={}", asOfDate, e);
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.PERFORMANCE_SNAPSHOT_FAILURE,
                    OpsAlertSeverity.ERROR,
                    "performance-snapshot-failure:" + asOfDate,
                    "Performance snapshot capture failed",
                    Map.of(
                            "asOfDate", asOfDate.toString(),
                            "source", "StrategyEodScheduler",
                            "error", e.getClass().getSimpleName(),
                            "message", e.getMessage() == null ? "-" : e.getMessage())));
        }
    }

    /**
     * VIX 현재가를 조회한다. (외부 호출용)
     */
    public BigDecimal getVix() {
        return yahooVixService.getVixPrice().orElse(null);
    }

    /**
     * QQQ 200MA를 조회한다. (외부 호출용)
     */
    public BigDecimal getQqq200Ma() {
        return yahooVixService.getQqq200Ma().orElse(null);
    }
}
