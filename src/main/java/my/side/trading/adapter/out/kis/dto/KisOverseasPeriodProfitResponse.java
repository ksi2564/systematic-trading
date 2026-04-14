package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisOverseasPeriodProfitResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("ctx_area_fk200") String ctxAreaFk200,
        @JsonProperty("ctx_area_nk200") String ctxAreaNk200,
        @JsonProperty("output1") List<Item> output1,
        @JsonProperty("output2") Summary output2
) {
    public List<Item> items() {
        return output1 == null ? List.of() : output1;
    }

    public String nextCtxAreaFk200() {
        return normalize(ctxAreaFk200);
    }

    public String nextCtxAreaNk200() {
        return normalize(ctxAreaNk200);
    }

    public KisOverseasPeriodProfitResponse withItems(List<Item> items) {
        return new KisOverseasPeriodProfitResponse(
                resultCode,
                messageCode,
                message,
                ctxAreaFk200,
                ctxAreaNk200,
                items,
                output2
        );
    }

    public record Item(
            @JsonProperty("trad_day") String tradeDate,
            @JsonProperty("ovrs_rlzt_pfls_amt") String realizedPnl,
            @JsonProperty("stck_sll_tlex") String fee,
            @JsonProperty("frst_bltn_exrt") String firstNoticeExchangeRate
    ) {
    }

    public record Summary(
            @JsonProperty("stck_sll_amt_smtl") String totalSellAmount,
            @JsonProperty("stck_buy_amt_smtl") String totalBuyAmount,
            @JsonProperty("smtl_fee1") String totalFee,
            @JsonProperty("excc_dfrm_amt") String exchangeDifferenceAmount,
            @JsonProperty("ovrs_rlzt_pfls_tot_amt") String totalRealizedPnl,
            @JsonProperty("tot_pftrt") String totalProfitRate
    ) {
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? "" : trimmed;
    }
}
