package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisOverseasPeriodProfitResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output1") List<Item> output1
) {
    public record Item(
            @JsonProperty("trad_dt") String tradeDate,
            @JsonProperty("rlzt_pfls") String realizedPnl,
            @JsonProperty("fee") String fee,
            @JsonProperty("tl_tax") String tax
    ) {
    }
}
