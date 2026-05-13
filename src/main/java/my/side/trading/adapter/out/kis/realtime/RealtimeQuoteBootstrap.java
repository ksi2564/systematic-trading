package my.side.trading.adapter.out.kis.realtime;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.client.KisOverseasRealtimeQuoteService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "realtime.quote.enabled", havingValue = "true", matchIfMissing = false)
public class RealtimeQuoteBootstrap implements ApplicationRunner {

    private final KisOverseasRealtimeQuoteService kisOverseasRealtimeQuoteService;

    @Override
    public void run(ApplicationArguments args) {
        // 앱 기동 시 핵심 ETF 3종목 실시간호가 자동 구독(QQQM, QLD, TQQQ)
        kisOverseasRealtimeQuoteService.startCoreEtfRealtimeQuotes();
    }
}
