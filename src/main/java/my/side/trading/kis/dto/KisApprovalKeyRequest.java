package my.side.trading.kis.dto;

public record KisApprovalKeyRequest(
        String grant_type,
        String appkey,
        String secretkey
) {
    public static KisApprovalKeyRequest of(String appKey, String secretkey) {
        return new KisApprovalKeyRequest("client_credentials", appKey, secretkey);
    }
}
