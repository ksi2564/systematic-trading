package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisOverseasBalanceResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output1") List<Item> items,
        @JsonProperty("output2") List<Currency> currencies
) {
    public record Item(
            @JsonProperty("pdno") String productCode,               // 심볼
            @JsonProperty("prdt_name") String productName,          // 주식 이름
            @JsonProperty("ord_psbl_qty1") String orderableQty,     // 주문가능수량(매도주문인듯)
            @JsonProperty("ccld_qty_smtl1") String qty,             // 보유수량
            @JsonProperty("avg_unpr3") String avgPrice,             // 평단가
            @JsonProperty("evlu_pfls_amt2") String profitLoss,      // 수익금액
            @JsonProperty("evlu_pfls_rt1") String profitLossRate    // 수익률
    ) {
    }

    public record Currency(
            @JsonProperty("crcy_cd") String currencyCode,           // USD
            @JsonProperty("crcy_cd_name") String currencyCodeName,  // 미국 달러
            @JsonProperty("frcr_dncl_amt_2") String usableAmt     // 외화 주문 가능 금액
    ) {
    }
}
