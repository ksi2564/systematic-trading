package my.side.trading.kis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kis")
public record KisProps(
        String baseUrl,
        String appKey,
        String appSecret,
        String accountNo,
        String cano,
        String acntPrdtCd
) {}
