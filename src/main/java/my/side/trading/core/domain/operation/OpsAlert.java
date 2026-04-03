package my.side.trading.core.domain.operation;

import java.util.Map;

public record OpsAlert(
        OpsAlertType type,
        OpsAlertSeverity severity,
        String dedupeKey,
        String message,
        Map<String, String> details
) {
    public OpsAlert {
        if (type == null) {
            throw new IllegalArgumentException("type은 필수");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity는 필수");
        }
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey는 필수");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message는 필수");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
