package my.side.trading.web.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.kis.dto.OverseasOrderRequest;
import my.side.trading.kis.dto.OverseasOrderResponse;
import my.side.trading.kis.client.KisOverseasOrderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/kis/overseas/order")
public class OverseasOrderController {

    private final KisOverseasOrderService kisOverseasOrderService;

    /**
     * 미국 주식 매수 주문 테스트용 엔드포인트
     */
    @PostMapping("/us/buy")
    public OverseasOrderResponse buyUsStock(@RequestBody OverseasOrderRequest request) {
        return kisOverseasOrderService.placeUsBuyOrder(request);
    }

    /**
     * 미국 주식 매도 주문 테스트용 엔드포인트
     */
    @PostMapping("/us/sell")
    public OverseasOrderResponse sellUsStock(@RequestBody OverseasOrderRequest request) {
        return kisOverseasOrderService.placeUsSellOrder(request);
    }
}
