package my.side.trading.core.application.operation;

import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;
import my.side.trading.testutil.FakeParameterChangeEventRepository;
import my.side.trading.testutil.FakeParameterRegistryRecordRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterRegistryServiceTest {

    @Test
    void 빈레지스트리면문서기준초기값으로부트스트랩한다() {
        FakeParameterRegistryRecordRepository recordRepository = new FakeParameterRegistryRecordRepository();
        FakeParameterChangeEventRepository eventRepository = new FakeParameterChangeEventRepository();
        MutableSnapshotProvider snapshotProvider = new MutableSnapshotProvider(defaultSnapshots("current"));
        ParameterRegistryService service = new ParameterRegistryService(recordRepository, eventRepository, snapshotProvider);

        List<?> records = service.getRegistry();

        assertThat(records).hasSize(ParameterRegistryKey.values().length);
        assertThat(recordRepository.count()).isEqualTo(ParameterRegistryKey.values().length);
        assertThat(eventRepository.findAll()).hasSize(ParameterRegistryKey.values().length);
    }

    @Test
    void 현재적용값과관측값이다르면충돌을반환한다() {
        MutableSnapshotProvider snapshotProvider = new MutableSnapshotProvider(defaultSnapshots("v1"));
        ParameterRegistryService service = new ParameterRegistryService(
                new FakeParameterRegistryRecordRepository(),
                new FakeParameterChangeEventRepository(),
                snapshotProvider);
        service.getRegistry();

        assertThatThrownBy(() -> service.recordChange(new RecordParameterChangeCommand(
                ParameterRegistryKey.VIX_THRESHOLD,
                "34",
                "alice",
                "vix review",
                ParameterRegistryStatus.ADOPTED,
                "근거",
                "검증",
                "결과",
                LocalDate.of(2026, 5, 10),
                List.of("report"))))
                .isInstanceOf(ParameterRegistryConflictException.class)
                .hasMessageContaining("observedEffectiveValue");
    }

    @Test
    void 변경기록을남기면현재상태와이력이함께갱신된다() {
        FakeParameterRegistryRecordRepository recordRepository = new FakeParameterRegistryRecordRepository();
        FakeParameterChangeEventRepository eventRepository = new FakeParameterChangeEventRepository();
        MutableSnapshotProvider snapshotProvider = new MutableSnapshotProvider(defaultSnapshots("v1"));
        ParameterRegistryService service = new ParameterRegistryService(recordRepository, eventRepository, snapshotProvider);
        service.getRegistry();

        Map<ParameterRegistryKey, String> updated = defaultSnapshots("v1");
        updated.put(ParameterRegistryKey.VIX_THRESHOLD, "40");
        snapshotProvider.replace(updated);

        var updatedRecord = service.recordChange(new RecordParameterChangeCommand(
                ParameterRegistryKey.VIX_THRESHOLD,
                "40",
                "alice",
                "threshold updated",
                ParameterRegistryStatus.ADOPTED,
                "실거래 검증 반영",
                "급변동 구간 성과 비교",
                "방어 성과가 안정적이었다.",
                LocalDate.of(2026, 6, 1),
                List.of("vol-report", "ops-note")));

        assertThat(updatedRecord.effectiveValue()).isEqualTo("40");
        assertThat(updatedRecord.status()).isEqualTo(ParameterRegistryStatus.ADOPTED);
        assertThat(updatedRecord.lastChangedBy()).isEqualTo("alice");
        assertThat(eventRepository.findRecentByKey(ParameterRegistryKey.VIX_THRESHOLD, 10))
                .first()
                .satisfies(event -> {
                    assertThat(event.previousValue()).isEqualTo("v1-VIX_THRESHOLD");
                    assertThat(event.newValue()).isEqualTo("40");
                    assertThat(event.requestedBy()).isEqualTo("alice");
                });
    }

    private Map<ParameterRegistryKey, String> defaultSnapshots(String prefix) {
        EnumMap<ParameterRegistryKey, String> snapshots = new EnumMap<>(ParameterRegistryKey.class);
        Arrays.stream(ParameterRegistryKey.values())
                .forEach(key -> snapshots.put(key, prefix + "-" + key.name()));
        return snapshots;
    }

    private static final class MutableSnapshotProvider implements EffectiveParameterSnapshotProvider {
        private Map<ParameterRegistryKey, String> snapshots;

        private MutableSnapshotProvider(Map<ParameterRegistryKey, String> snapshots) {
            this.snapshots = Map.copyOf(snapshots);
        }

        @Override
        public Map<ParameterRegistryKey, String> snapshotAll() {
            return snapshots;
        }

        private void replace(Map<ParameterRegistryKey, String> newSnapshots) {
            this.snapshots = Map.copyOf(newSnapshots);
        }
    }
}
