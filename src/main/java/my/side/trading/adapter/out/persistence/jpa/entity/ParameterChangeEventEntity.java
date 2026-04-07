package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import my.side.trading.adapter.out.persistence.jpa.converter.StringListConverter;
import my.side.trading.core.domain.parameter.ParameterChangeEvent;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "parameter_change_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ParameterChangeEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "registry_key", length = 64, nullable = false)
    private ParameterRegistryKey key;

    @Column(name = "previous_value", length = 500)
    private String previousValue;

    @Column(name = "new_value", nullable = false, length = 500)
    private String newValue;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ParameterRegistryStatus status;

    @Column(name = "basis", nullable = false, length = 500)
    private String basis;

    @Column(name = "validation_method", nullable = false, length = 500)
    private String validationMethod;

    @Column(name = "validation_summary", nullable = false, length = 1000)
    private String validationSummary;

    @Column(name = "next_review_date", nullable = false)
    private LocalDate nextReviewDate;

    @Convert(converter = StringListConverter.class)
    @Column(name = "related_artifacts", nullable = false, length = 2000)
    private List<String> relatedArtifacts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ParameterChangeEventEntity from(ParameterChangeEvent event) {
        return ParameterChangeEventEntity.builder()
                .id(event.id())
                .key(event.key())
                .previousValue(event.previousValue())
                .newValue(event.newValue())
                .requestedBy(event.requestedBy())
                .reason(event.reason())
                .status(event.status())
                .basis(event.basis())
                .validationMethod(event.validationMethod())
                .validationSummary(event.validationSummary())
                .nextReviewDate(event.nextReviewDate())
                .relatedArtifacts(event.relatedArtifacts())
                .createdAt(event.createdAt())
                .build();
    }

    public ParameterChangeEvent toDomain() {
        return new ParameterChangeEvent(
                id,
                key,
                previousValue,
                newValue,
                requestedBy,
                reason,
                status,
                basis,
                validationMethod,
                validationSummary,
                nextReviewDate,
                relatedArtifacts,
                createdAt);
    }
}
