package my.side.trading.core.application.execution;

public enum ExecutionBlockReason {
    KILL_SWITCH_ON("KILL_SWITCH_ON"),
    PAPER_MODE_BLOCKS_LIVE_EXECUTION("PAPER_MODE_BLOCKS_LIVE_EXECUTION"),
    AUTO_EXECUTION_REQUIRES_AUTO_LIVE("AUTO_EXECUTION_REQUIRES_AUTO_LIVE"),
    KPI_BREACH("KPI_BREACH"),
    RISK_LIMIT_BREACH("RISK_LIMIT_BREACH"),
    ORDER_CONFIRMATION_REQUIRED("ORDER_CONFIRMATION_REQUIRED"),
    EXECUTION_DISABLED("EXECUTION_DISABLED");

    private final String code;

    ExecutionBlockReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
