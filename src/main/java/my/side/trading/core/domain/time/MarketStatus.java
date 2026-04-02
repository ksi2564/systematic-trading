package my.side.trading.core.domain.time;

public enum MarketStatus {
    REGULAR,
    HOLIDAY,
    EARLY_CLOSE,
    DATA_UNCERTAIN;

    public boolean allowsAutomatedRebalance() {
        return this == REGULAR;
    }

    public boolean allowsScheduledEod() {
        return this == REGULAR || this == EARLY_CLOSE;
    }
}
