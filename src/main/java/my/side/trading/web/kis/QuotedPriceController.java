package my.side.trading.web.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.kis.dto.QuotedPriceResponse;
import my.side.trading.kis.client.KisOverseasQuotedPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QuotedPriceController {
    private final KisOverseasQuotedPriceService kisOverseasQuotedPriceService;

    @GetMapping("/kis/quoted-price")
    public QuotedPriceResponse getQuotedPrice(@RequestParam("symbol") String symbol) {
        return kisOverseasQuotedPriceService.getQuotedPrice(symbol);
    }
}
