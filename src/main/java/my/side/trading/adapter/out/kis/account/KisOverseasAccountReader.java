package my.side.trading.adapter.out.kis.account;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.client.KisOverseasBalanceService;
import my.side.trading.adapter.out.kis.client.KisOverseasPsAmountService;
import my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse;
import my.side.trading.adapter.out.kis.dto.KisOverseasPsAmountResponse;
import my.side.trading.core.domain.portfolio.OverseasAccountReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import static my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse.Currency;
import static my.side.trading.adapter.out.kis.dto.KisOverseasBalanceResponse.Item;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisOverseasAccountReader implements OverseasAccountReader {

    private static final String CASH_REF_SYMBOL = "QQQM";
    private static final BigDecimal CASH_REF_PRICE = new BigDecimal("100.00");
    private static final String PSAMOUNT_EXCHANGE = "NASD"; // 나스닥

    private final KisOverseasBalanceService balanceService;
    private final KisOverseasPsAmountService psAmountService;

    @Override
    public AccountSnapshot getAccountSnapshot() {
        KisOverseasBalanceResponse res = balanceService.getOverseasBalance();

        BigDecimal cash = getUsdOrderableCash(res.currencies());
        List<AccountPosition> positions = extractPositions(res.items());

        return new AccountSnapshot(cash, positions);
    }

    private BigDecimal getUsdOrderableCash(List<Currency> currencies) {
        try {
            KisOverseasPsAmountResponse ps = psAmountService.getPsAmount(PSAMOUNT_EXCHANGE, CASH_REF_SYMBOL, CASH_REF_PRICE);

            if (ps == null || ps.output() == null) {
                log.warn("psamount 응답이 없어 balance usableAmt로 대체합니다.");
                return extractUsdCashFromBalance(currencies);
            }
            if (!"0".equals(ps.resultCode())) {
                log.warn("psamount 호출이 실패해 balance usableAmt로 대체합니다. rt_cd={}, msg_cd={}, msg1={}",
                        ps.resultCode(), ps.messageCode(), ps.message());
                return extractUsdCashFromBalance(currencies);
            }

            // 해외주문가능금액(ovrs_ord_psbl_amt) = 주문가능달러
            BigDecimal overseasOrderableAmount = toBigDecimalOrNull(ps.output().overseasOrderableAmount());

            if (overseasOrderableAmount != null) return overseasOrderableAmount;

            log.warn("overseasOrderableAmount가 없어 balance usableAmt로 대체합니다. currency={}, msg={}/{}",
                    ps.output().tradeCurrencyCode(), ps.messageCode(), ps.message());
            return extractUsdCashFromBalance(currencies);

        } catch (Exception e) {
            log.warn("psamount 호출에 실패해 balance usableAmt로 대체합니다. reason={}", e.toString());
            return extractUsdCashFromBalance(currencies);
        }
    }

    private BigDecimal toBigDecimalOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        t = t.replace(",", "");
        try {
            return new BigDecimal(t);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 기존에는 잔고에서 cash를 가져왔지만 실제 주문 가능 금액과 다른 값일 수 있다. (현금 + 평가금액으로 보임)
     * 평소에는 사용하지 않고, psAmountService.getPsAmount()가 동작하지 않을 때만 대체 경로로 사용한다.
     *
     * @param currencies
     * @return
     */
    private BigDecimal extractUsdCashFromBalance(List<Currency> currencies) {
        return currencies.stream()
                .filter(c -> "USD".equalsIgnoreCase(c.currencyCode()))
                .findFirst()
                .map(c -> new BigDecimal(c.usableAmt()))
                .orElse(BigDecimal.ZERO);
    }

    private List<AccountPosition> extractPositions(List<Item> items) {
        return items.stream()
                .map(i -> new AccountPosition(
                        i.productCode(),                 // "QQQM", "QLD", "TQQQ"
                        new BigDecimal(i.qty()),         // 보유 수량
                        new BigDecimal(i.avgPrice())     // 매입평균단가
                ))
                .toList();
    }
}
