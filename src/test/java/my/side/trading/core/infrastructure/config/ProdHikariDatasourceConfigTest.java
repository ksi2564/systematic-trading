package my.side.trading.core.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProdHikariDatasourceConfigTest {

    private final PropertySource<?> prodProperties = loadProdProperties();

    @Test
    void 운영_hikari_pool_기본값을_명시한다() {
        assertThat(prodProperties.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:5}");
        assertThat(prodProperties.getProperty("spring.datasource.hikari.minimum-idle"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE:1}");
        assertThat(prodProperties.getProperty("spring.datasource.hikari.max-lifetime"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_MAX_LIFETIME_MS:1500000}");
        assertThat(prodProperties.getProperty("spring.datasource.hikari.idle-timeout"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MS:600000}");
        assertThat(prodProperties.getProperty("spring.datasource.hikari.keepalive-time"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_KEEPALIVE_TIME_MS:300000}");
        assertThat(prodProperties.getProperty("spring.datasource.hikari.connection-timeout"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT_MS:30000}");
        assertThat(prodProperties.getProperty("spring.datasource.hikari.validation-timeout"))
                .isEqualTo("${SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT_MS:5000}");
    }

    private static PropertySource<?> loadProdProperties() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application-prod", new ClassPathResource("application-prod.yml"))
                    .getFirst();
        } catch (IOException e) {
            throw new IllegalStateException("application-prod.yml을 읽을 수 없습니다.", e);
        }
    }
}
