package my.side.trading.adapter.out.kis.dto;

/**
 * 해외주식 실시간 호가 구독(티커/시장코드 기준)
 *
 * @param symbol 종목코드 (예: "QQQM", "QQQ")
 * @param excd   시장코드 (예: "NAS", "NYS", "AMS")
 */
public record SymbolTarget(String symbol, String excd) {
}
