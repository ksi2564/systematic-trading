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
                FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(
                                Map.of("QQQ", new BigDecimal("100.00")));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.5"),
                                new BigDecimal("0.5"),
                                new BigDecimal("0.25"));

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                // 현재: QQQ 1주 보유, 총 자산 $100
                Portfolio portfolio = new Portfolio(
                                new BigDecimal("0"),
                                List.of(new Position("QQQ", new BigDecimal("1"), new BigDecimal("90"),
                                                new BigDecimal("100"))));

                // 목표비중: QQQ 0% -> 전량 매도 해야 함
                WeightSet targetWeights = new WeightSet(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

                OrderAndCashDelta ocd = factory.fromTargetWeight(
                                "QQQ",
                                ExecutionOrderSide.SELL,
                                targetWeights,
                                portfolio,
                                BigDecimal.ZERO,
                                new BigDecimal("0.3")).orElseThrow();

                assertThat(ocd.order().getQuantity()).isEqualTo(1);
                assertThat(ocd.cashDelta().signum()).isPositive();
        }

        @Test
        void 매수수량이_잔여현금_초과_시_매수가능한_수량만큼_상한_처리되고_현금변화량은_음수() {
                FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(
                                Map.of("QQQ", new BigDecimal("100.00")));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.5"),
                                new BigDecimal("0.5"),
                                new BigDecimal("0.25"));

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                // 현재: 현금만 $200 보유
                Portfolio portfolio = new Portfolio(new BigDecimal("200"), List.of());

                // 목표비중: QQQ 100% -> 가능한 만큼 매수
                WeightSet targetWeights = new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);

                OrderAndCashDelta ocd = factory.fromTargetWeight(
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                targetWeights,
                                portfolio,
                                new BigDecimal("200.00"),
                                new BigDecimal("0.3")).orElseThrow();

                assertThat(ocd.order().getSide()).isEqualTo(ExecutionOrderSide.BUY);
                assertThat(ocd.order().getQuantity()).isGreaterThanOrEqualTo(1);
                assertThat(ocd.cashDelta().signum()).isNegative();
        }

        @Test
        void 수량이_0이면_빈_결과_반환() {
                FakeRealtimePriceProvider priceProvider = new FakeRealtimePriceProvider(
                                Map.of("QQQ", new BigDecimal("100.00")));
                MarketLikePricingPolicy pricing = new MarketLikePricingPolicy(
                                new BigDecimal("0.5"),
                                new BigDecimal("0.5"),
                                new BigDecimal("0.25"));

                ExecutionOrderFactory factory = new ExecutionOrderFactory(priceProvider, pricing);

                // 이미 목표비중에 도달한 경우
                Portfolio portfolio = new Portfolio(new BigDecimal("0"), List.of(
                                new Position("QQQ", new BigDecimal("10"), new BigDecimal("100"),
                                                new BigDecimal("100"))));

                // 목표비중: QQQ 100% (이미 달성)
                WeightSet targetWeights = new WeightSet(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);

                assertThat(factory.fromTargetWeight(
                                "QQQ",
                                ExecutionOrderSide.BUY,
                                targetWeights,
                                portfolio,
                                BigDecimal.ZERO,
                                new BigDecimal("0.3"))).isEmpty();
        }
}
