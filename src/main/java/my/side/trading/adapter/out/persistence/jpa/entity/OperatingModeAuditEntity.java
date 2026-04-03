package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeTransitionType;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;

import java.time.Instant;

@Entity
@Table(name = "operation_mode_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OperatingModeAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_mode", length = 32)
    private OperatingMode previousMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_mode", length = 32, nullable = false)
    private OperatingMode targetMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_type", length = 32, nullable = false)
    private OperatingModeTransitionType transitionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", length = 32, nullable = false)
    private OperatingModeTriggerSource triggerSource;

    @Column(name = "trigger_code", length = 64)
    private String triggerCode;

    @Column(name = "requested_by", length = 128, nullable = false)
    private String requestedBy;

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static OperatingModeAuditEntity from(OperatingModeAuditEvent event) {
        return OperatingModeAuditEntity.builder()
                .id(event.id())
                .previousMode(event.previousMode())
                .targetMode(event.targetMode())
                .transitionType(event.transitionType())
                .triggerSource(event.triggerSource())
                .triggerCode(event.triggerCode())
                .requestedBy(event.requestedBy())
                .reason(event.reason())
                .approvedBy(event.approvedBy())
                .approvedAt(event.approvedAt())
                .createdAt(event.createdAt())
                .build();
    }

    public OperatingModeAuditEvent toDomain() {
        return new OperatingModeAuditEvent(
                id,
                previousMode,
                targetMode,
                transitionType,
                triggerSource,
                triggerCode,
                requestedBy,
                reason,
                approvedBy,
                approvedAt,
                createdAt);
    }
}
