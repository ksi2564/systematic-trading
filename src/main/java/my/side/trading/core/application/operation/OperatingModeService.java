package my.side.trading.core.application.operation;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeAuditRepository;
import my.side.trading.core.domain.operation.OperatingModeControlRepository;
import my.side.trading.core.domain.operation.OperatingModeReader;
import my.side.trading.core.domain.operation.OperatingModeTransitionType;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperatingModeService implements OperatingModeReader {

    private static final String SYSTEM_ACTOR = "system";
    private static final String BOOTSTRAP_REASON = "Bootstrap operating mode from configuration";
    private static final String SAFETY_OVERRIDE_REASON = "AUTO_LIVE bootstrap requires manual approval record";

    private static final Map<OpsAlertType, OperatingMode> AUTO_DEMOTION_TARGETS = autoDemotionTargets();

    private final TradingOperationProps operationProps;
    private final OperatingModeControlRepository controlRepository;
    private final OperatingModeAuditRepository auditRepository;

    @Override
    public OperatingMode currentMode() {
        return controlRepository.findCurrentMode()
                .orElseGet(this::bootstrapMode);
    }

    @Override
    public boolean hasManualApprovalRecord() {
        if (!operationProps.autoLiveGate().requireManualApprovalRecord()) {
            return true;
        }
        return auditRepository.hasManualAutoLiveApproval();
    }

    public OperatingModeStatus getCurrentStatus(int historyLimit) {
        return new OperatingModeStatus(
                currentMode(),
                hasManualApprovalRecord(),
                recentHistory(historyLimit));
    }

    public List<OperatingModeAuditEvent> recentHistory(int limit) {
        return auditRepository.findRecent(normalizeLimit(limit));
    }

    @Transactional
    public void initializeIfMissing() {
        if (controlRepository.findCurrentMode().isPresent()) {
            return;
        }
        bootstrapMode();
    }

    @Transactional
    public OperatingModeChangeResult changeMode(OperatingMode targetMode, String requestedBy, String reason) {
        if (targetMode == null) {
            throw new IllegalArgumentException("targetMode is required");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }

        OperatingMode currentMode = currentMode();
        if (currentMode == targetMode) {
            return new OperatingModeChangeResult(currentMode, false, hasManualApprovalRecord(), null);
        }

        Instant now = Instant.now();
        OperatingModeAuditEvent auditEvent = saveTransition(
                currentMode,
                targetMode,
                transitionType(currentMode, targetMode),
                OperatingModeTriggerSource.MANUAL_API,
                null,
                requestedBy,
                reason,
                targetMode == OperatingMode.AUTO_LIVE ? requestedBy : null,
                targetMode == OperatingMode.AUTO_LIVE ? now : null,
                now);
        return new OperatingModeChangeResult(targetMode, true, hasManualApprovalRecord(), auditEvent);
    }

    @Transactional
    public void applySystemAlert(OpsAlert alert) {
        OperatingMode targetMode = AUTO_DEMOTION_TARGETS.get(alert.type());
        if (targetMode == null) {
            return;
        }

        OperatingMode currentMode = currentMode();
        if (currentMode != OperatingMode.AUTO_LIVE || currentMode == targetMode) {
            return;
        }

        saveTransition(
                currentMode,
                targetMode,
                OperatingModeTransitionType.DEMOTION,
                OperatingModeTriggerSource.SYSTEM,
                alert.type().name(),
                SYSTEM_ACTOR,
                "Automatic demotion triggered by " + alert.type().name(),
                null,
                null,
                Instant.now());
    }

    private OperatingMode bootstrapMode() {
        OperatingMode configuredMode = operationProps.mode();
        Instant now = Instant.now();

        if (configuredMode == OperatingMode.AUTO_LIVE && operationProps.autoLiveGate().requireManualApprovalRecord()) {
            return saveTransition(
                    null,
                    OperatingMode.MANUAL_LIVE,
                    OperatingModeTransitionType.SAFETY_OVERRIDE,
                    OperatingModeTriggerSource.SYSTEM,
                    "AUTO_LIVE_REQUIRES_APPROVAL",
                    SYSTEM_ACTOR,
                    SAFETY_OVERRIDE_REASON,
                    null,
                    null,
                    now).targetMode();
        }

        return saveTransition(
                null,
                configuredMode,
                OperatingModeTransitionType.BOOTSTRAP,
                OperatingModeTriggerSource.SYSTEM,
                "CONFIG_BOOTSTRAP",
                SYSTEM_ACTOR,
                BOOTSTRAP_REASON,
                null,
                null,
                now).targetMode();
    }

    private OperatingModeAuditEvent saveTransition(
            OperatingMode previousMode,
            OperatingMode targetMode,
            OperatingModeTransitionType transitionType,
            OperatingModeTriggerSource triggerSource,
            String triggerCode,
            String requestedBy,
            String reason,
            String approvedBy,
            Instant approvedAt,
            Instant createdAt
    ) {
        controlRepository.saveCurrentMode(targetMode);
        return auditRepository.save(new OperatingModeAuditEvent(
                null,
                previousMode,
                targetMode,
                transitionType,
                triggerSource,
                triggerCode,
                requestedBy,
                reason,
                approvedBy,
                approvedAt,
                createdAt));
    }

    private OperatingModeTransitionType transitionType(OperatingMode previousMode, OperatingMode targetMode) {
        if (previousMode == targetMode) {
            return OperatingModeTransitionType.LATERAL_CHANGE;
        }
        return targetMode.ordinal() > previousMode.ordinal()
                ? OperatingModeTransitionType.PROMOTION
                : OperatingModeTransitionType.DEMOTION;
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, 100);
    }

    private static Map<OpsAlertType, OperatingMode> autoDemotionTargets() {
        EnumMap<OpsAlertType, OperatingMode> targets = new EnumMap<>(OpsAlertType.class);
        targets.put(OpsAlertType.KPI_BREACH, OperatingMode.MANUAL_LIVE);
        targets.put(OpsAlertType.DATA_UNCERTAIN, OperatingMode.MANUAL_LIVE);
        targets.put(OpsAlertType.EOD_FAILURE, OperatingMode.MANUAL_LIVE);
        targets.put(OpsAlertType.UNRESOLVED_ORDER, OperatingMode.MANUAL_LIVE);
        targets.put(OpsAlertType.RISK_LIMIT_BREACH, OperatingMode.MANUAL_LIVE);
        targets.put(OpsAlertType.BROKER_API_FAILURE, OperatingMode.PAPER);
        targets.put(OpsAlertType.KILL_SWITCH_ON, OperatingMode.PAPER);
        return Map.copyOf(targets);
    }
}
