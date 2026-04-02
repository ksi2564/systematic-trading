package my.side.trading.core.application.execution.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketLikePricingPolicyTest {

    @Test
    void 틱_오프셋과_수수료_정책을_계산할_수_있다() {
        MarketLikePricingPolicy policy = new MarketLikePricingPolicy(
                new BigDecimal("0.01"),
                0,
                0,
                1,
                1,
                new BigDecimal("0.25"),
                3,
                2000
        );

        assertThat(policy.tickSize()).isEqualByComparingTo("0.01");
        assertThat(policy.priceTicksForAttempt(my.side.trading.core.domain.execution.order.ExecutionOrderSide.BUY, 1))
                .isEqualTo(0);
        assertThat(policy.priceTicksForAttempt(my.side.trading.core.domain.execution.order.ExecutionOrderSide.BUY, 3))
                .isEqualTo(2);
        assertThat(policy.feeRate()).isEqualByComparingTo("0.0025");
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.retryWaitMs()).isEqualTo(2000);
    }
}
