package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.guard.KillSwitchReader;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExecutionGuard {

    private final TradingExecutionProps executionProps;
    private final TradingOperationProps operationProps;
    private final KillSwitchReader killSwitchReader;

    public void requireExecutionAllowed(ExecutionTriggerType triggerType) {
        getExecutionBlockReason(triggerType)
                .ifPresent(reason -> {
                    throw new ExecutionBlockedException(reason);
                });
    }

    public void requireOrderPlacementAllowed() {
        getOrderPlacementBlockReason()
                .ifPresent(reason -> {
                    throw new ExecutionBlockedException(reason);
                });
    }

    public Optional<ExecutionBlockReason> getExecutionBlockReason(ExecutionTriggerType triggerType) {
        if (killSwitchReader.isKillSwitchOn()) {
            return Optional.of(ExecutionBlockReason.KILL_SWITCH_ON);
        }
        if (operationProps.mode() == OperatingMode.PAPER) {
            return Optional.of(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION);
        }
        if (triggerType == ExecutionTriggerType.AUTOMATED && operationProps.mode() != OperatingMode.AUTO_LIVE) {
            return Optional.of(ExecutionBlockReason.AUTO_EXECUTION_REQUIRES_AUTO_LIVE);
        }
        if (!executionProps.enabled()) {
            return Optional.of(ExecutionBlockReason.EXECUTION_DISABLED);
        }
        return Optional.empty();
    }

    public boolean canExecute(ExecutionTriggerType triggerType) {
        return getExecutionBlockReason(triggerType).isEmpty();
    }

    public OperatingMode currentMode() {
        return operationProps.mode();
    }

    public boolean isExecutionEnabled() {
        return executionProps.enabled();
    }

    public boolean isKillSwitchOn() {
        return killSwitchReader.isKillSwitchOn();
    }

    public ExecutionGuardSnapshot snapshot() {
        TradingOperationProps.AutoLiveGateProps autoLiveGate = operationProps.autoLiveGate();
        return new ExecutionGuardSnapshot(
                operationProps.mode(),
                executionProps.enabled(),
                killSwitchReader.isKillSwitchOn(),
                getExecutionBlockReason(ExecutionTriggerType.MANUAL).orElse(null),
                getExecutionBlockReason(ExecutionTriggerType.AUTOMATED).orElse(null),
                new ExecutionGuardSnapshot.AutoLiveGateSnapshot(
                        autoLiveGate.requiredConsecutiveEodSuccessDays(),
                        autoLiveGate.requireZeroPendingOrders(),
                        autoLiveGate.requireZeroDuplicateSignalJobs(),
                        autoLiveGate.requireManualApprovalRecord()));
    }

    private Optional<ExecutionBlockReason> getOrderPlacementBlockReason() {
        if (killSwitchReader.isKillSwitchOn()) {
            return Optional.of(ExecutionBlockReason.KILL_SWITCH_ON);
        }
        if (operationProps.mode() == OperatingMode.PAPER) {
            return Optional.of(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION);
        }
        if (!executionProps.enabled()) {
            return Optional.of(ExecutionBlockReason.EXECUTION_DISABLED);
        }
        return Optional.empty();
    }
}
