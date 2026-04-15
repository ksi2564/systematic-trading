package my.side.trading.core.domain.portfolio;

/**
 * QQQ 자동매매 시스템에서 운용하는 종목 목록.
 * QQQ: 기본 복리 엔진
 * QLD: 2배 레버리지
 * TQQQ: 3배 레버리지
 */
public enum Ticker {
    QQQ,
    QLD,
    TQQQ;

    /**
     * 문자열로부터 Ticker를 생성한다.
     * 
     * @param symbol 종목 심볼 (예: "QQQ")
     * @return 해당 Ticker 열거형
     * @throws IllegalArgumentException 지원하지 않는 심볼인 경우
     */
    public static Ticker from(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol은 필수입니다");
        }
        return switch (symbol.toUpperCase()) {
            case "QQQ" -> QQQ;
            case "QLD" -> QLD;
            case "TQQQ" -> TQQQ;
            default -> throw new IllegalArgumentException("지원하지 않는 심볼: " + symbol);
        };
    }
}
