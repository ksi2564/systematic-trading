package my.side.trading.adapter.in.web.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.client.KisOverseasRealtimeQuoteService;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
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
    public ApiResponse<RealtimeQuoteSubscriptionResponse> subscribeOverseasRealtime(
            @RequestParam("symbol") String symbol,
            @RequestParam(defaultValue = "NAS") String excd
    ) {
        return ApiResponse.success(RealtimeQuoteSubscriptionResponse.from(
                kisOverseasRealtimeQuoteService.subscribeRealtimeQuote(symbol, excd)
        ));
    }
}
