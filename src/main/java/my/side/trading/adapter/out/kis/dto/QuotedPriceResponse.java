package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuotedPriceResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") Item item
) {
    public record Item(
            @JsonProperty("rsym") String rawSymbol,
            @JsonProperty("zdiv") String decimalPlaces,
            @JsonProperty("base") String prevClosePrice,
            @JsonProperty("pvol") String prevVolume,
            @JsonProperty("last") String lastPrice,
            @JsonProperty("sign") String changeSign,
            @JsonProperty("diff") String priceDiff,
            @JsonProperty("rate") String changeRate,
            @JsonProperty("tvol") String volume,
            @JsonProperty("tamt") String tradingValue,
            @JsonProperty("ordy") String isBuyable
    ) {
    }
}
