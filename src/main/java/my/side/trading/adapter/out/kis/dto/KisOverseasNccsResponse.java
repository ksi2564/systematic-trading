package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisOverseasNccsResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("output") List<Item> output
) {
    public record Item(
            @JsonProperty("odno") String orderNo,
            @JsonProperty("pdno") String pdno,
            @JsonProperty("sll_buy_dvsn_cd") String sideCode,
            @JsonProperty("sll_buy_dvsn_cd_name") String sideCodeName,
            @JsonProperty("ft_ord_qty") String orderQty,        // 주문수량
            @JsonProperty("ft_ccld_qty") String filledQty,      // 체결된 수량
            @JsonProperty("nccs_qty") String unfilledQty,       // 미체결된 수량
            @JsonProperty("ft_ord_unpr3") String orderPrice,    // 주문가격
            @JsonProperty("ft_ccld_unpr3") String filledPrice,  // 체결된 가격
            @JsonProperty("ft_ccld_amt3") String filledAmount   // 체결된 금액
    ) {
    }
}
