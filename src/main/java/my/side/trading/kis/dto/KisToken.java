package my.side.trading.kis.dto;

public record KisToken(
        String accessToken,
        long expiresAtMills // 만료 시각(epoch millis)
) {
    public boolean isValid() {
        return System.currentTimeMillis() < expiresAtMills;
    }
}
