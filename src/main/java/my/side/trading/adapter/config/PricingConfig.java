package my.side.trading.adapter.config;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import my.side.trading.core.infrastructure.config.TradingPricingProps;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PricingConfig {

    @Bean
    public MarketLikePricingPolicy marketLikePricingPolicy(TradingPricingProps props) {
        return new MarketLikePricingPolicy(
                props.tickSize(),
                props.initialBuyTicks(),
                props.initialSellTicks(),
                props.retryBuyTicks(),
                props.retrySellTicks(),
                props.feePct(),
                props.maxAttempts(),
                props.retryWaitMs()
        );
    }
}
