package my.side.trading.core.application.operation;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.parameter.ParameterChangeEvent;
import my.side.trading.core.domain.parameter.ParameterChangeEventRepository;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryRecord;
import my.side.trading.core.domain.parameter.ParameterRegistryRecordRepository;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ParameterRegistryService {

    private static final String SYSTEM_ACTOR = "system";
    private static final String BOOTSTRAP_REASON = "Initial registry bootstrap from documented policy";
    private static final String DEFAULT_VALIDATION_SUMMARY = "문서 기준 초기 정책값이며 검증 결과는 아직 별도 등록되지 않았다.";
    private static final LocalDate DEFAULT_NEXT_REVIEW_DATE = LocalDate.of(2026, 5, 2);

    private final ParameterRegistryRecordRepository recordRepository;
    private final ParameterChangeEventRepository changeEventRepository;
    private final EffectiveParameterSnapshotProvider snapshotProvider;
    private final Clock clock;

    @Transactional
    public List<ParameterRegistryRecord> getRegistry() {
        ensureBootstrapped();
        return recordRepository.findAll().stream()
                .sorted(Comparator.comparingInt(record -> record.key().sortOrder()))
                .toList();
    }

    @Transactional
    public List<ParameterChangeEvent> getHistory(ParameterRegistryKey key, int limit) {
        ensureBootstrapped();
        return changeEventRepository.findRecentByKey(key, normalizeLimit(limit));
    }

    @Transactional
    public ParameterRegistryRecord recordChange(RecordParameterChangeCommand command) {
        ensureBootstrapped();

        String currentEffectiveValue = snapshotProvider.snapshot(command.key());
        if (!currentEffectiveValue.equals(command.observedEffectiveValue())) {
            throw new ParameterRegistryConflictException(
                    "observedEffectiveValue does not match current effective value. observed=%s, current=%s"
                            .formatted(command.observedEffectiveValue(), currentEffectiveValue));
        }

        ParameterRegistryRecord currentRecord = recordRepository.findByKey(command.key())
                .orElseThrow(() -> new IllegalStateException("parameter registry record is missing: " + command.key()));
        Instant now = Instant.now(clock);

        changeEventRepository.save(new ParameterChangeEvent(
                null,
                command.key(),
                currentRecord.effectiveValue(),
                currentEffectiveValue,
                command.requestedBy(),
                command.reason(),
                command.status(),
                command.basis(),
                command.validationMethod(),
                command.validationSummary(),
                command.nextReviewDate(),
                command.relatedArtifacts(),
                now));

        ParameterRegistryRecord updatedRecord = new ParameterRegistryRecord(
                command.key(),
                currentEffectiveValue,
                command.status(),
                command.basis(),
                command.validationMethod(),
                command.validationSummary(),
                command.nextReviewDate(),
                command.relatedArtifacts(),
                command.requestedBy(),
                now);
        return recordRepository.save(updatedRecord);
    }

    @Transactional
    public void ensureBootstrapped() {
        if (recordRepository.count() > 0) {
            return;
        }

        Instant now = Instant.now(clock);
        for (ParameterRegistryKey key : Arrays.stream(ParameterRegistryKey.values())
                .sorted(Comparator.comparingInt(ParameterRegistryKey::sortOrder))
                .toList()) {
            String effectiveValue = snapshotProvider.snapshot(key);
            ParameterRegistryRecord record = new ParameterRegistryRecord(
                    key,
                    effectiveValue,
                    ParameterRegistryStatus.PROVISIONAL,
                    key.defaultBasis(),
                    key.defaultValidationMethod(),
                    DEFAULT_VALIDATION_SUMMARY,
                    DEFAULT_NEXT_REVIEW_DATE,
                    key.defaultRelatedArtifacts(),
                    SYSTEM_ACTOR,
                    now);
            recordRepository.save(record);
            changeEventRepository.save(new ParameterChangeEvent(
                    null,
                    key,
                    null,
                    effectiveValue,
                    SYSTEM_ACTOR,
                    BOOTSTRAP_REASON,
                    ParameterRegistryStatus.PROVISIONAL,
                    key.defaultBasis(),
                    key.defaultValidationMethod(),
                    DEFAULT_VALIDATION_SUMMARY,
                    DEFAULT_NEXT_REVIEW_DATE,
                    key.defaultRelatedArtifacts(),
                    now));
        }
    }

    private int normalizeLimit(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, 100);
    }
}
