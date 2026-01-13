package my.side.trading.adapter.config;

import my.side.trading.core.application.execution.pricing.MarketLikePricingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class PricingConfig {

    @Bean
    public MarketLikePricingPolicy marketLikePricingPolicy() {
        return new MarketLikePricingPolicy(
                new BigDecimal("0.5"),
                new BigDecimal("0.5"),
                new BigDecimal("0.25")
        );
    }
}
