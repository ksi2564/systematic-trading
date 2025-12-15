package my.side.trading.core.infrastructure.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.portfolio.OverseasAccountReader;
import my.side.trading.kis.client.KisOverseasBalanceService;
import my.side.trading.kis.dto.KisOverseasBalanceResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KisOverseasAccountReader implements OverseasAccountReader {

    private final KisOverseasBalanceService balanceService;

    @Override
    public AccountSnapshot getAccountSnapshot() {
        KisOverseasBalanceResponse resp = balanceService.getOverseasBalance();

        BigDecimal cash = extractUsdCash(resp);
        List<AccountPosition> positions = extractPositions(resp);

        return new AccountSnapshot(cash, positions);
    }

    private BigDecimal extractUsdCash(KisOverseasBalanceResponse resp) {
        return resp.currencies().stream()
                .filter(c -> "USD".equalsIgnoreCase(c.currencyCode()))
                .findFirst()
                .map(c -> new BigDecimal(c.usableAmt()))
                .orElse(BigDecimal.ZERO); // USD 항목이 없으면 0
    }

    private List<AccountPosition> extractPositions(KisOverseasBalanceResponse resp) {
        return resp.items().stream()
                .map(i -> new AccountPosition(
                        i.productCode(),                 // "QQQ", "QLD", "TQQQ"
                        new BigDecimal(i.qty()),         // 보유 수량
                        new BigDecimal(i.avgPrice())     // 매입평균단가
                ))
                .toList();
    }
}
