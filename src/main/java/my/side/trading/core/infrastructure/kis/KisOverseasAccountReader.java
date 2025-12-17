package my.side.trading.core.infrastructure.kis;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.portfolio.OverseasAccountReader;
import my.side.trading.adapter.out.kis.client.KisOverseasBalanceService;
import my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import static my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse.Currency;
import static my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse.Item;

@Component
@RequiredArgsConstructor
public class KisOverseasAccountReader implements OverseasAccountReader {

    private final KisOverseasBalanceService balanceService;

    @Override
    public AccountSnapshot getAccountSnapshot() {
        KisOverseasBalanceResponse res = balanceService.getOverseasBalance();

        BigDecimal cash = extractUsdCash(res.currencies());
        List<AccountPosition> positions = extractPositions(res.items());

        return new AccountSnapshot(cash, positions);
    }

    private BigDecimal extractUsdCash(List<Currency> currencies) {
        return currencies.stream()
                .filter(c -> "USD".equalsIgnoreCase(c.currencyCode()))
                .findFirst()
                .map(c -> new BigDecimal(c.usableAmt()))
                .orElse(BigDecimal.ZERO); // USD 항목이 없으면 0
    }

    private List<AccountPosition> extractPositions(List<Item> items) {
        return items.stream()
                .map(i -> new AccountPosition(
                        i.productCode(),                 // "QQQ", "QLD", "TQQQ"
                        new BigDecimal(i.qty()),         // 보유 수량
                        new BigDecimal(i.avgPrice())     // 매입평균단가
                ))
                .toList();
    }
}
