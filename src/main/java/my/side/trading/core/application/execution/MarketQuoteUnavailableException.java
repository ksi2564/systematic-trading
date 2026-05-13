package my.side.trading.core.application.execution;

public class MarketQuoteUnavailableException extends IllegalStateException {

    public MarketQuoteUnavailableException(String symbol) {
        super("quote cache miss: " + symbol);
    }
}
