package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KisOverseasCcnlResponse(
        @JsonProperty("rt_cd") String resultCode,
        @JsonProperty("msg_cd") String messageCode,
        @JsonProperty("msg1") String message,
        @JsonProperty("ctx_area_fk200") String ctxAreaFk200,
        @JsonProperty("ctx_area_nk200") String ctxAreaNk200,
        @JsonProperty("output") List<Item> output
) {
    public KisOverseasCcnlResponse(String resultCode, String messageCode, String message, List<Item> output) {
        this(resultCode, messageCode, message, "", "", output);
    }

    public KisOverseasCcnlResponse withOutput(List<Item> output) {
        return new KisOverseasCcnlResponse(resultCode, messageCode, message, ctxAreaFk200, ctxAreaNk200, output);
    }

    public record Item(
            @JsonProperty("ord_dt") String orderDate,
            @JsonProperty("odno") String orderNo,
            @JsonProperty("pdno") String pdno,
            @JsonProperty("sll_buy_dvsn_cd") String sideCode,
            @JsonProperty("ord_tmd") String orderTime,
            @JsonProperty("ft_ord_qty") String orderQty,
            @JsonProperty("ft_ord_unpr3") String orderPrice,
            @JsonProperty("ft_ccld_qty") String filledQty,
            @JsonProperty("nccs_qty") String unfilledQty
    ) {
        public Item(
                String orderNo,
                String pdno,
                String sideCode,
                String orderQty,
                String orderPrice,
                String filledQty,
                String unfilledQty
        ) {
            this(null, orderNo, pdno, sideCode, null, orderQty, orderPrice, filledQty, unfilledQty);
        }
    }
}
