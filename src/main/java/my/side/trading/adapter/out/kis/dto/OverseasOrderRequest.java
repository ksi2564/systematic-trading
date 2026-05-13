package my.side.trading.adapter.out.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param cano
 * @param accountProductCode
 * @param overseasExchangeCode
 * @param productNo
 * @param orderQuantity
 * @param orderPrice
 * @param orderServerDivisionCode
 * @param orderType
 */
public record OverseasOrderRequest(
        @JsonProperty("CANO")
        String cano,                // 종합계좌번호 (8-2체계 계좌번호 앞 8자리)

        @JsonProperty("ACNT_PRDT_CD")
        String accountProductCode,  // 계좌상품코드 (8-2체계 계좌번호 뒤 2자리)

        @JsonProperty("OVRS_EXCG_CD")
        String overseasExchangeCode, // 해외거래소코드 (예: "NASD", "AMEX")

        @JsonProperty("PDNO")
        String productNo,           // 상품번호 (종목코드: QQQM, QLD, TQQQ)

        @JsonProperty("ORD_QTY")
        String orderQuantity,       // 주문수량

        @JsonProperty("OVRS_ORD_UNPR")
        String orderPrice,          // 1주당 가격 (시장가도 "0"으로 채워서 보냄)

        @JsonProperty("ORD_SVR_DVSN_CD")
        String orderServerDivisionCode, // 주문서버구분코드 ("0" 고정 예시)

        @JsonProperty("ORD_DVSN")
        String orderType            // 주문구분 (지정가/시장가 코드 - 엑셀 참조)
) {
}
