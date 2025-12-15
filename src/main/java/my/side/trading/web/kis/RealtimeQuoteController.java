package my.side.trading.web.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.kis.client.KisOverseasRealtimeQuoteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RealtimeQuoteController {

    private final KisOverseasRealtimeQuoteService kisOverseasRealtimeQuoteService;

    /**
     * 테스트/디버깅용 단일 종목 실시간호가 구독 엔드포인트
     */
    @GetMapping("/kis/realtime/overseas")
    public String subscribeOverseasRealtime(
            @RequestParam("symbol") String symbol,
            @RequestParam(defaultValue = "NAS") String excd
    ) {
        kisOverseasRealtimeQuoteService.subscribeRealtimeQuote(symbol, excd);
        return "Subscribed " + symbol + " (" + excd + ") realtime quote.";
    }
}
