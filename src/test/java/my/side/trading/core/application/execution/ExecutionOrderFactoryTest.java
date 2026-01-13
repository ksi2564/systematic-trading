package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.execution.plan.OrderIntent;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionOrderFactoryTest {

    @Test
    void 매도수량이_보유수량_초과_시_보유수량_상한_처리되고_현금변화량은_양수() {
        FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(Map.of("QQQ", new BigDecimal("100.00")));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.5"),  // buy buffer 0.5%
                new BigDecimal("0.5"),  // sell buffer 0.5%
                new BigDecimal("0.25")  // fee 0.25%
        );

        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

        Portfolio portfolio = new Portfolio(
                new BigDecimal("0"),
                List.of(new Position("QQQ", new BigDecimal("1"), new BigDecimal("90"), new BigDecimal("100")))
        );

        // 502달러(약 5주) 만큼 팔고 싶으나 보유수량이 1주
        OrderIntent intent = new OrderIntent("QQQ", ExecutionOrderSide.SELL, new BigDecimal("502.00"), "test");

        OrderAndCashDelta ocd = factory.fromIntentWithCashDelta(intent, portfolio, new BigDecimal("0"))
                .orElseThrow();

        assertThat(ocd.order().getQuantity()).isEqualTo(1);
        assertThat(ocd.cashDelta().signum()).isPositive();
    }

    @Test
    void 매수수량이_잔여현금_초과_시_매수가능한_수량만큼_상한_처리되고_현금변화량은_음수() {
        FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(Map.of("QQQ", new BigDecimal("100.00")));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.5"),
                new BigDecimal("0.5"),
                new BigDecimal("0.25")
        );

        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

        Portfolio portfolio = new Portfolio(new BigDecimal("0"), List.of());

        // notional=1000이면 원래 9~10주를 사고 싶지만 remainingCash가 200달러면 1주만 구매가능(수수료 포함 계산)
        OrderIntent intent = new OrderIntent("QQQ", ExecutionOrderSide.BUY, new BigDecimal("1000.00"), "test");

        OrderAndCashDelta ocd = factory.fromIntentWithCashDelta(intent, portfolio, new BigDecimal("200.00"))
                .orElseThrow();

        assertThat(ocd.order().getSide()).isEqualTo(ExecutionOrderSide.BUY);
        assertThat(ocd.order().getQuantity()).isEqualTo(1);
        assertThat(ocd.cashDelta().signum()).isNegative();
    }

    @Test
    void 수량이_0이면_빈_결과_반환() {
        FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(Map.of("QQQ", new BigDecimal("100.00")));
        MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                new BigDecimal("0.5"),
                new BigDecimal("0.5"),
                new BigDecimal("0.25")
        );

        ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

        Portfolio portfolio = new Portfolio(new BigDecimal("0"), List.of());

        // remainingCash가 너무 적거나, 주문금액이 시장가보다 작으면 0주 -> empty
        OrderIntent intent = new OrderIntent("QQQ", ExecutionOrderSide.BUY, new BigDecimal("50.00"), "test");

        assertThat(factory.fromIntentWithCashDelta(intent, portfolio, new BigDecimal("10.00"))).isEmpty();
    }
}
