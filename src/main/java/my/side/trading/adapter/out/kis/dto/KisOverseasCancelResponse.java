package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 해외주식 주문취소 응답
 */
public record KisOverseasCancelResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") Output output) {
    public record Output(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") String krxOrgNo,
            @JsonProperty("ODNO") String orderNo,
            @JsonProperty("ORD_TMD") String orderTime) {
    }

    public boolean isSuccess() {
        return "0".equals(resultCode);
    }
}
