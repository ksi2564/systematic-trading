package my.side.trading.core.application.execution;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.testutil.FakeRealtimePriceProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionOrderFactoryTest {

        @Test
        void 매도수량이_보유수량_초과_시_보유수량_상한_처리되고_현금변화량은_양수() {
                FakeRealtimePriceProvider priceProvider = FakeRealtimePriceProvider.withLastPrices(
                                Map.of("QQQM", new BigDecimal("100.00")));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000);

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                // 현재: QQQM 1주 보유, 총 자산 $100
                Portfolio portfolio = new Portfolio(
                                new BigDecimal("0"),
                                List.of(new Position("QQQM", new BigDecimal("1"), new BigDecimal("90"),
                                                new BigDecimal("100"))));

                // 목표비중: QQQM 0% -> 전량 매도 해야 함
                WeightSet targetWeights = new WeightSet(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

                OrderAndCashDelta ocd = factory.fromTargetWeight(
                                "QQQM",
                                ExecutionOrderSide.SELL,
                                targetWeights,
                                portfolio,
                                BigDecimal.ZERO).orElseThrow();

                assertThat(ocd.order().getQuantity()).isEqualTo(1);
                assertThat(ocd.cashDelta().signum()).isPositive();
        }

        @Test
        void 매수수량이_잔여현금_초과_시_매수가능한_수량만큼_상한_처리되고_현금변화량은_음수() {
                FakeRealtimePriceProvider priceProvider = FakeRealtimePriceProvider.withLastPrices(
                                Map.of("QQQM", new BigDecimal("100.00")));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000);

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                // 현재: 현금만 $200 보유
                Portfolio portfolio = new Portfolio(new BigDecimal("200"), List.of());

                // 목표비중: QQQM 100% -> 가능한 만큼 매수
                WeightSet targetWeights = new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);

                OrderAndCashDelta ocd = factory.fromTargetWeight(
                                "QQQM",
                                ExecutionOrderSide.BUY,
                                targetWeights,
                                portfolio,
                                new BigDecimal("200.00")).orElseThrow();

                assertThat(ocd.order().getSide()).isEqualTo(ExecutionOrderSide.BUY);
                assertThat(ocd.order().getQuantity()).isGreaterThanOrEqualTo(1);
                assertThat(ocd.cashDelta().signum()).isNegative();
        }

        @Test
        void 수량이_0이면_빈_결과_반환() {
                FakeRealtimePriceProvider priceProvider = FakeRealtimePriceProvider.withLastPrices(
                                Map.of("QQQM", new BigDecimal("100.00")));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000);

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                // 이미 목표비중에 도달한 경우
                Portfolio portfolio = new Portfolio(new BigDecimal("0"), List.of(
                                new Position("QQQM", new BigDecimal("10"), new BigDecimal("100"),
                                                new BigDecimal("100"))));

                // 목표비중: QQQM 100% (이미 달성)
                WeightSet targetWeights = new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);

                assertThat(factory.fromTargetWeight(
                                "QQQM",
                                ExecutionOrderSide.BUY,
                                targetWeights,
                                portfolio,
                                BigDecimal.ZERO)).isEmpty();
        }

        @Test
        void 매수는_bestAsk_매도는_bestBid를_기준가격으로_사용한다() {
                FakeRealtimePriceProvider priceProvider = FakeRealtimePriceProvider.withLastPrices(
                                Map.of("QQQM", new BigDecimal("100.00")));
                priceProvider.updateQuote("QQQM", new BigDecimal("100.00"), new BigDecimal("99.80"),
                                new BigDecimal("100.20"));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.01"), 0, 0, 1, 1, new BigDecimal("0.25"), 3, 2000);

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                OrderAndCashDelta buy = factory.fromTargetWeight(
                                "QQQM",
                                ExecutionOrderSide.BUY,
                                new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO),
                                new Portfolio(new BigDecimal("1000"), List.of()),
                                new BigDecimal("1000")).orElseThrow();

                OrderAndCashDelta sell = factory.fromTargetWeight(
                                "QQQM",
                                ExecutionOrderSide.SELL,
                                new WeightSet(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                                new Portfolio(new BigDecimal("0"), List.of(
                                                new Position("QQQM", new BigDecimal("1"), new BigDecimal("90"),
                                                                new BigDecimal("100")))),
                                BigDecimal.ZERO).orElseThrow();

                assertThat(buy.order().getRefPrice()).isEqualByComparingTo("100.20");
                assertThat(buy.order().getLimitPrice()).isEqualByComparingTo("100.20");
                assertThat(sell.order().getRefPrice()).isEqualByComparingTo("99.80");
                assertThat(sell.order().getLimitPrice()).isEqualByComparingTo("99.80");
        }
}
