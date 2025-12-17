package my.side.trading.adapter.in.web.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse;
import my.side.trading.adapter.out.kis.client.KisOverseasBalanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class KisOverseasBalanceController {

    private final KisOverseasBalanceService overseasBalanceService;

    @GetMapping("/kis/overseas-balance")
    public KisOverseasBalanceResponse getOverseasBalance() {
        return overseasBalanceService.getOverseasBalance();
    }
}
