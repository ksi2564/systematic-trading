package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisOverseasCcnlResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<Item> output
) {
    public record Item(
            @JsonProperty("odno") String orderNo,
            @JsonProperty("pdno") String pdno,
            @JsonProperty("sll_buy_dvsn_cd") String sideCode,
            @JsonProperty("ft_ord_qty") String orderQty,
            @JsonProperty("ft_ord_unpr3") String orderPrice,
            @JsonProperty("ft_ccld_qty") String filledQty,
            @JsonProperty("nccs_qty") String unfilledQty
    ) {}
}
