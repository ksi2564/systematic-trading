package my.side.trading.adapter.out.kis.dto;

import java.time.Instant;

public record KisTokenDiagnosticsSnapshot(
        boolean cached,
        boolean valid,
        Instant expiresAt,
        long ttlSeconds,
        int tokenLength,
        String fingerprint
) {
    public static KisTokenDiagnosticsSnapshot empty() {
        return new KisTokenDiagnosticsSnapshot(false, false, null, 0, 0, null);
    }
}
