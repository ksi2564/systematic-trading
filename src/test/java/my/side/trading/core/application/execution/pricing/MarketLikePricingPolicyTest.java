package my.side.trading.core.application.execution.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketLikePricingPolicyTest {

    @Test
    void 퍼센트_입력하고_소수_비율로_변환_사용_가능() {
        MarketLikePricingPolicy policy = new MarketLikePricingPolicy(
                new BigDecimal("0.5"),  // 0.5%
                new BigDecimal("0.5"),  // 0.5%
                new BigDecimal("0.25")  // 0.25%
        );

        assertThat(policy.buyBuffer()).isEqualByComparingTo("0.005");    // 0.5% => 0.005
        assertThat(policy.sellBuffer()).isEqualByComparingTo("0.005");
        assertThat(policy.feeRate()).isEqualByComparingTo("0.0025");     // 0.25% => 0.0025
    }
}
