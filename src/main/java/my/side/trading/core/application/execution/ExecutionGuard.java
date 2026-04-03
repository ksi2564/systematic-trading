package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.application.operation.OperationsKpiService;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.guard.KillSwitchReader;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeReader;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertPublisher;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.infrastructure.config.TradingExecutionProps;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExecutionGuard {

    private final TradingExecutionProps executionProps;
    private final TradingOperationProps operationProps;
    private final OperatingModeReader operatingModeReader;
    private final KillSwitchReader killSwitchReader;
    private final OperationsKpiService operationsKpiService;
    private final OpsAlertPublisher opsAlertPublisher;

    public void requireExecutionAllowed(ExecutionTriggerType triggerType) {
        getExecutionBlockReason(triggerType)
                .ifPresent(reason -> {
                    publishExecutionBlockAlert(reason, triggerType);
                    throw new ExecutionBlockedException(reason);
                });
    }

    public void requireOrderPlacementAllowed() {
        getOrderPlacementBlockReason()
                .ifPresent(reason -> {
                    publishOrderPlacementAlert(reason);
                    throw new ExecutionBlockedException(reason);
                });
    }

    public Optional<ExecutionBlockReason> getExecutionBlockReason(ExecutionTriggerType triggerType) {
        OperatingMode currentMode = operatingModeReader.currentMode();
        if (killSwitchReader.isKillSwitchOn()) {
            return Optional.of(ExecutionBlockReason.KILL_SWITCH_ON);
        }
        if (currentMode == OperatingMode.PAPER) {
            return Optional.of(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION);
        }
        if (triggerType == ExecutionTriggerType.AUTOMATED && currentMode != OperatingMode.AUTO_LIVE) {
            return Optional.of(ExecutionBlockReason.AUTO_EXECUTION_REQUIRES_AUTO_LIVE);
        }
        if (!executionProps.enabled()) {
            return Optional.of(ExecutionBlockReason.EXECUTION_DISABLED);
        }
        if (triggerType == ExecutionTriggerType.AUTOMATED && operationsKpiService.hasAutoLiveBreach()) {
            return Optional.of(ExecutionBlockReason.KPI_BREACH);
        }
        return Optional.empty();
    }

    public boolean canExecute(ExecutionTriggerType triggerType) {
        return getExecutionBlockReason(triggerType).isEmpty();
    }

    public OperatingMode currentMode() {
        return operatingModeReader.currentMode();
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
                operatingModeReader.currentMode(),
                executionProps.enabled(),
                killSwitchReader.isKillSwitchOn(),
                getExecutionBlockReason(ExecutionTriggerType.MANUAL).orElse(null),
                getExecutionBlockReason(ExecutionTriggerType.AUTOMATED).orElse(null),
                new ExecutionGuardSnapshot.AutoLiveGateSnapshot(
                        autoLiveGate.requiredConsecutiveEodSuccessDays(),
                        autoLiveGate.requireZeroPendingOrders(),
                        autoLiveGate.requireZeroDuplicateSignalJobs(),
                        autoLiveGate.requireManualApprovalRecord(),
                        operatingModeReader.hasManualApprovalRecord()));
    }

    private Optional<ExecutionBlockReason> getOrderPlacementBlockReason() {
        OperatingMode currentMode = operatingModeReader.currentMode();
        if (killSwitchReader.isKillSwitchOn()) {
            return Optional.of(ExecutionBlockReason.KILL_SWITCH_ON);
        }
        if (currentMode == OperatingMode.PAPER) {
            return Optional.of(ExecutionBlockReason.PAPER_MODE_BLOCKS_LIVE_EXECUTION);
        }
        if (!executionProps.enabled()) {
            return Optional.of(ExecutionBlockReason.EXECUTION_DISABLED);
        }
        return Optional.empty();
    }

    private void publishExecutionBlockAlert(ExecutionBlockReason reason, ExecutionTriggerType triggerType) {
        if (reason == ExecutionBlockReason.KILL_SWITCH_ON) {
            publishKillSwitchAlert("execution", triggerType.name());
            return;
        }
        if (reason == ExecutionBlockReason.KPI_BREACH && triggerType == ExecutionTriggerType.AUTOMATED) {
            var snapshot = operationsKpiService.snapshot();
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("triggerType", triggerType.name());
            details.put("mode", operatingModeReader.currentMode().name());
            details.put("marketDate", snapshot.marketDate().toString());
            details.put("breaches", snapshot.breaches().toString());
            opsAlertPublisher.publish(new OpsAlert(
                    OpsAlertType.KPI_BREACH,
                    OpsAlertSeverity.ERROR,
                    "kpi-breach:" + snapshot.marketDate(),
                    "Automated execution blocked by KPI breach",
                    details));
        }
    }

    private void publishOrderPlacementAlert(ExecutionBlockReason reason) {
        if (reason == ExecutionBlockReason.KILL_SWITCH_ON) {
            publishKillSwitchAlert("order-placement", null);
        }
    }

    private void publishKillSwitchAlert(String source, String triggerType) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("source", source);
        details.put("mode", operatingModeReader.currentMode().name());
        if (triggerType != null) {
            details.put("triggerType", triggerType);
        }
        opsAlertPublisher.publish(new OpsAlert(
                OpsAlertType.KILL_SWITCH_ON,
                OpsAlertSeverity.ERROR,
                "kill-switch-on",
                "Kill switch is active",
                details));
    }
}
