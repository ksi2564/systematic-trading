package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KisOverseasPsAmountResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") Output output
) {
    public record Output(
            @JsonProperty("tr_crcy_cd") String tradeCurrencyCode,        // 거래통화코드 (USD 등)
            @JsonProperty("ord_psbl_frcr_amt") String orderableFxAmount, // 주문가능외화금액 = 출금가능금액
            @JsonProperty("ovrs_ord_psbl_amt") String overseasOrderableAmount // 해외주문가능금액(한투 주문화면 내 외화 탭의 주문 가능 금액)
    ) {
    }
}
