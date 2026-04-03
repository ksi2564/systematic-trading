package my.side.trading.core.domain.operation;

public interface OperatingModeReader {
    OperatingMode currentMode();

    boolean hasManualApprovalRecord();
}
