package my.side.trading.adapter.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.converter.StringListConverter;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryRecord;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "parameter_registry_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ParameterRegistryRecordEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "registry_key", length = 64, nullable = false)
    private ParameterRegistryKey key;

    @Column(name = "effective_value", nullable = false, length = 500)
    private String effectiveValue;

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

    @Column(name = "last_changed_by", nullable = false, length = 128)
    private String lastChangedBy;

    @Column(name = "last_changed_at", nullable = false)
    private Instant lastChangedAt;

    public static ParameterRegistryRecordEntity from(ParameterRegistryRecord record) {
        return ParameterRegistryRecordEntity.builder()
                .key(record.key())
                .effectiveValue(record.effectiveValue())
                .status(record.status())
                .basis(record.basis())
                .validationMethod(record.validationMethod())
                .validationSummary(record.validationSummary())
                .nextReviewDate(record.nextReviewDate())
                .relatedArtifacts(record.relatedArtifacts())
                .lastChangedBy(record.lastChangedBy())
                .lastChangedAt(record.lastChangedAt())
                .build();
    }

    public ParameterRegistryRecord toDomain() {
        return new ParameterRegistryRecord(
                key,
                effectiveValue,
                status,
                basis,
                validationMethod,
                validationSummary,
                nextReviewDate,
                relatedArtifacts,
                lastChangedBy,
                lastChangedAt);
    }
}
