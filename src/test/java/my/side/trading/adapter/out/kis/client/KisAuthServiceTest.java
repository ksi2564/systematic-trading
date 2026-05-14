package my.side.trading.adapter.out.kis.client;

import com.github.benmanes.caffeine.cache.Caffeine;
import my.side.trading.adapter.out.kis.config.KisProps;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KisAuthServiceTest {

    @Test
    void approval_key_발급_실패_예외는_raw_body를_노출하지_않는다() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse
                        .create(HttpStatus.BAD_REQUEST)
                        .body("{\"approval_key\":\"secret-approval-key\",\"appsecret\":\"secret-app\",\"CANO\":\"12345678\"}")
                        .build()))
                .build();
        KisAuthService service = new KisAuthService(
                webClient,
                new KisProps("https://example.test", "app-key", "app-secret", "12345678-01", "12345678", "01", null, null),
                Caffeine.newBuilder().build());

        assertThatThrownBy(service::issueApprovalKey)
                .hasMessageNotContaining("secret-approval-key")
                .hasMessageNotContaining("secret-app")
                .hasMessageNotContaining("12345678")
                .hasMessageContaining("\"approval_key\":\"***\"");
    }
}
