package my.side.trading.core.domain.execution.plan;

public enum RebalanceType {
    THRESHOLD,      // 비중 오차로 발동
    PHASE_CHANGE    // phase 전환으로 발동
}
