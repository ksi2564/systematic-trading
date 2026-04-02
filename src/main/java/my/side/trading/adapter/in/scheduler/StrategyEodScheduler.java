package my.side.trading.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasQuotedPriceService;
import my.side.trading.adapter.out.kis.dto.QuotedPriceResponse;
import my.side.trading.adapter.out.yahoo.YahooVixService;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.strategy.StrategyStateEodService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEodScheduler {

    private final KisOverseasQuotedPriceService quotedPriceService;
    private final StrategyStateEodService eodService;
    private final YahooVixService yahooVixService;
    private final MarketCalendarService marketCalendarService;

    @Value("${trading.scheduling.enabled:false}")
    private boolean enabled;

    // KST 기준 미장 마감 이후 15분 여유
    // TODO: 추후 계절시간 감안 필요
    @Scheduled(cron = "0 15 06 * * TUE-SAT", zone = "Asia/Seoul") // 06:15 KST
    public void runScheduledEod() {
        if (!enabled) {
            return;
        }
        LocalDate marketDate = marketCalendarService.currentMarketDate();
        var marketStatus = marketCalendarService.getMarketStatus(marketDate);
        if (!marketStatus.allowsScheduledEod()) {
            log.info("[SCHED] eod skipped by market calendar | marketDate={}, marketStatus={}",
                    marketDate,
                    marketStatus);
            return;
        }
        runEod(marketDate);
    }

    public void runManualEod() {
        runEod(marketCalendarService.currentMarketDate());
    }

    private void runEod(LocalDate asOfDate) {
        QuotedPriceResponse res = quotedPriceService.getQuotedPrice("QQQ");
        BigDecimal close = new BigDecimal(res.item().prevClosePrice());

        // VIX 및 200MA 조회 (Circuit Breaker 용)
        BigDecimal vix = yahooVixService.getVixPrice().orElse(null);
        BigDecimal qqqMa200 = yahooVixService.getQqq200Ma().orElse(null);

        log.info("Circuit Breaker data: VIX={}, QQQ_200MA={}", vix, qqqMa200);

        eodService.runEod(asOfDate, close);

        log.info("EOD updated: asOfDate={}, qqqClose={}", asOfDate, close);
    }

    /**
     * VIX 현재가 조회 (외부 호출용)
     */
    public BigDecimal getVix() {
        return yahooVixService.getVixPrice().orElse(null);
    }

    /**
     * QQQ 200MA 조회 (외부 호출용)
     */
    public BigDecimal getQqq200Ma() {
        return yahooVixService.getQqq200Ma().orElse(null);
    }
}
