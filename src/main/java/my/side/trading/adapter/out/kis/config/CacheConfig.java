package my.side.trading.adapter.out.kis.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import my.side.trading.adapter.out.kis.dto.KisToken;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    public Cache<String, KisToken> kisTokenCache() {
        return Caffeine.newBuilder()
                .build();
    }
}
