package my.side.trading.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OverseasOrderResponse(
        @JsonProperty("rt_cd")
        String resultCode,  // 0: 성공, 그 외: 실패

        @JsonProperty("msg_cd")
        String messageCode,

        @JsonProperty("msg1")
        String message,

        @JsonProperty("output")
        Output output
) {
    public record Output(
            @JsonProperty("KRX_FWDG_ORD_ORGNO")
            String orgNo,     // 한국거래소전송주문조직번호(한국투자증권 시스템 영업점 코드)

            @JsonProperty("ODNO")
            String orderNo,   // 주문번호

            @JsonProperty("ORD_TMD")
            String orderTime  // 주문시각 (HHMMSS)
    ) {
    }
}
