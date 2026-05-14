package my.side.trading.shared.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerTest {

    @Test
    void 인증값과_계좌_식별자는_마스킹한다() {
        String raw = """
                Authorization=Bearer raw-access-token
                {"approval_key":"secret-approval-key","appsecret":"secret-app","CANO":"12345678","ACNT_PRDT_CD":"01","output":{"key":"aes-key","iv":"aes-iv"}}
                계좌=1234567890 key=aes-key iv=aes-iv
                """;

        String sanitized = SensitiveDataSanitizer.sanitize(raw);

        assertThat(sanitized)
                .doesNotContain("raw-access-token")
                .doesNotContain("secret-approval-key")
                .doesNotContain("secret-app")
                .doesNotContain("12345678")
                .doesNotContain("aes-key")
                .doesNotContain("aes-iv")
                .doesNotContain("1234567890")
                .contains("Bearer ***")
                .contains("\"approval_key\":\"***\"")
                .contains("\"CANO\":\"***\"")
                .contains("계좌=***")
                .contains("key=***")
                .contains("iv=***");
    }

    @Test
    void alert_detail_key가_민감하면_value_전체를_마스킹한다() {
        Map<String, String> sanitized = SensitiveDataSanitizer.sanitizeMap(Map.of(
                "approval_key", "secret-approval-key",
                "message", "Bearer raw-access-token",
                "symbol", "QQQM"));

        assertThat(sanitized)
                .containsEntry("approval_key", "***")
                .containsEntry("message", "Bearer ***")
                .containsEntry("symbol", "QQQM");
    }

    @Test
    void Throwable_summary도_민감정보를_숨긴다() {
        Throwable throwable = new IllegalStateException("body={approval_key=secret-approval-key, CANO=12345678}");

        String sanitized = SensitiveDataSanitizer.sanitizeThrowable(throwable);

        assertThat(sanitized)
                .startsWith("IllegalStateException:")
                .doesNotContain("secret-approval-key")
                .doesNotContain("12345678")
                .contains("approval_key=***")
                .contains("CANO=***");
    }

    @Test
    void webhook_url의_token_path도_마스킹한다() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "500 from POST https://discord.com/api/webhooks/123456/secret-webhook-token");

        assertThat(sanitized)
                .doesNotContain("123456")
                .doesNotContain("secret-webhook-token")
                .contains("https://***/webhooks/***");
    }
}
