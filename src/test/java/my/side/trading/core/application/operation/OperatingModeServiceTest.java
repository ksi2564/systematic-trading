package my.side.trading.core.application.operation;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.testutil.FakeOperatingModeAuditRepository;
import my.side.trading.testutil.FakeOperatingModeControlRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperatingModeServiceTest {

    @Test
    void bootstrapsConfiguredModeWhenControlMissing() {
        FakeOperatingModeControlRepository controlRepository = new FakeOperatingModeControlRepository();
        FakeOperatingModeAuditRepository auditRepository = new FakeOperatingModeAuditRepository();
        OperatingModeService service = new OperatingModeService(
                operationProps(OperatingMode.PAPER, true),
                controlRepository,
                auditRepository);

        OperatingMode currentMode = service.currentMode();

        assertThat(currentMode).isEqualTo(OperatingMode.PAPER);
        assertThat(controlRepository.findCurrentMode()).contains(OperatingMode.PAPER);
        assertThat(auditRepository.findAll()).singleElement()
                .satisfies(event -> {
                    assertThat(event.previousMode()).isNull();
                    assertThat(event.targetMode()).isEqualTo(OperatingMode.PAPER);
                    assertThat(event.triggerSource()).isEqualTo(OperatingModeTriggerSource.SYSTEM);
                });
    }

    @Test
    void downgradesAutoLiveBootstrapToManualLiveWhenApprovalRequired() {
        FakeOperatingModeControlRepository controlRepository = new FakeOperatingModeControlRepository();
        FakeOperatingModeAuditRepository auditRepository = new FakeOperatingModeAuditRepository();
        OperatingModeService service = new OperatingModeService(
                operationProps(OperatingMode.AUTO_LIVE, true),
                controlRepository,
                auditRepository);

        OperatingMode currentMode = service.currentMode();

        assertThat(currentMode).isEqualTo(OperatingMode.MANUAL_LIVE);
        assertThat(service.hasManualApprovalRecord()).isFalse();
        assertThat(auditRepository.findAll()).singleElement()
                .satisfies(event -> {
                    assertThat(event.targetMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
                    assertThat(event.triggerCode()).isEqualTo("AUTO_LIVE_REQUIRES_APPROVAL");
                });
    }

    @Test
    void manualPromotionToAutoLiveCreatesApprovalAudit() {
        FakeOperatingModeControlRepository controlRepository =
                new FakeOperatingModeControlRepository(OperatingMode.MANUAL_LIVE);
        FakeOperatingModeAuditRepository auditRepository = new FakeOperatingModeAuditRepository();
        OperatingModeService service = new OperatingModeService(
                operationProps(OperatingMode.PAPER, true),
                controlRepository,
                auditRepository);

        OperatingModeChangeResult result = service.changeMode(
                OperatingMode.AUTO_LIVE,
                "alice",
                "all checks passed");

        assertThat(result.changed()).isTrue();
        assertThat(result.currentMode()).isEqualTo(OperatingMode.AUTO_LIVE);
        assertThat(result.manualApprovalRecorded()).isTrue();
        assertThat(result.auditEvent()).isNotNull();
        assertThat(result.auditEvent().approvedBy()).isEqualTo("alice");
        assertThat(result.auditEvent().approvedAt()).isEqualTo(result.auditEvent().createdAt());
    }

    @Test
    void automaticDemotionRunsOnlyFromAutoLive() {
        FakeOperatingModeControlRepository controlRepository =
                new FakeOperatingModeControlRepository(OperatingMode.AUTO_LIVE);
        FakeOperatingModeAuditRepository auditRepository = new FakeOperatingModeAuditRepository();
        OperatingModeService service = new OperatingModeService(
                operationProps(OperatingMode.PAPER, true),
                controlRepository,
                auditRepository);

        service.applySystemAlert(alert(OpsAlertType.KPI_BREACH));

        assertThat(service.currentMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
        assertThat(auditRepository.findAll()).singleElement()
                .satisfies(event -> {
                    assertThat(event.previousMode()).isEqualTo(OperatingMode.AUTO_LIVE);
                    assertThat(event.targetMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
                    assertThat(event.triggerCode()).isEqualTo("KPI_BREACH");
                });
    }

    @Test
    void automaticDemotionDoesNothingWhenAlreadyManualLive() {
        FakeOperatingModeControlRepository controlRepository =
                new FakeOperatingModeControlRepository(OperatingMode.MANUAL_LIVE);
        FakeOperatingModeAuditRepository auditRepository = new FakeOperatingModeAuditRepository();
        OperatingModeService service = new OperatingModeService(
                operationProps(OperatingMode.PAPER, true),
                controlRepository,
                auditRepository);

        service.applySystemAlert(alert(OpsAlertType.BROKER_API_FAILURE));

        assertThat(service.currentMode()).isEqualTo(OperatingMode.MANUAL_LIVE);
        assertThat(auditRepository.findAll()).isEmpty();
    }

    @Test
    void sameModeChangeIsNoOp() {
        FakeOperatingModeControlRepository controlRepository =
                new FakeOperatingModeControlRepository(OperatingMode.PAPER);
        FakeOperatingModeAuditRepository auditRepository = new FakeOperatingModeAuditRepository();
        OperatingModeService service = new OperatingModeService(
                operationProps(OperatingMode.PAPER, true),
                controlRepository,
                auditRepository);

        OperatingModeChangeResult result = service.changeMode(OperatingMode.PAPER, "alice", "keep paper");

        assertThat(result.changed()).isFalse();
        assertThat(result.auditEvent()).isNull();
        assertThat(auditRepository.findAll()).isEmpty();
    }

    private TradingOperationProps operationProps(OperatingMode mode, boolean requireManualApprovalRecord) {
        return new TradingOperationProps(
                mode,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, requireManualApprovalRecord),
                new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                new TradingOperationProps.RiskLimitProps(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new TradingOperationProps.AlertsProps(false, 30));
    }

    private OpsAlert alert(OpsAlertType type) {
        return new OpsAlert(type, OpsAlertSeverity.ERROR, "dedupe-" + type.name(), type.name(), Map.of());
    }
}
