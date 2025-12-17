package my.side.trading.adapter.out.kis.dto;

public record KisTokenRequest(
        String grant_type,
        String appkey,
        String appsecret
) {
    public static KisTokenRequest of(String appKey, String appSecret) {
        return new KisTokenRequest("client_credentials", appKey, appSecret);
    }
}
