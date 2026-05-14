package my.side.trading.adapter.out.operation;

import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingOpsAlertPublisherTest {

    @Test
    void alert_로그는_민감정보를_마스킹한다(CapturedOutput output) {
        LoggingOpsAlertPublisher publisher = new LoggingOpsAlertPublisher();

        publisher.publish(new OpsAlert(
                OpsAlertType.BROKER_API_FAILURE,
                OpsAlertSeverity.ERROR,
                "broker-api-failure:approval_key=secret-approval-key",
                "failure body Bearer raw-access-token",
                Map.of(
                        "approval_key", "secret-approval-key",
                        "message", "{\"CANO\":\"12345678\",\"key\":\"aes-key\"}")));

        assertThat(output).doesNotContain("secret-approval-key");
        assertThat(output).doesNotContain("raw-access-token");
        assertThat(output).doesNotContain("12345678");
        assertThat(output).doesNotContain("aes-key");
        assertThat(output).contains("approval_key=***");
    }
}
