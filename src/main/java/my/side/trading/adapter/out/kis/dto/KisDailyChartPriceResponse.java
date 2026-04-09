package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisDailyChartPriceResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<Item> output,
        @JsonProperty("output2") List<Item> output2
) {
    public List<Item> items() {
        if (output2 != null && !output2.isEmpty()) {
            return output2;
        }
        return output == null ? List.of() : output;
    }

    public record Item(
            @JsonAlias({"xymd", "stck_bsop_date", "date"}) String businessDate,
            @JsonAlias({"clos", "stck_clpr", "last", "t_rate", "ovrs_nmix_prpr"}) String closePrice
    ) {
    }
}
