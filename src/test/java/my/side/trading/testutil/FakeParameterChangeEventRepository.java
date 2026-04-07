package my.side.trading.testutil;

import my.side.trading.core.domain.parameter.ParameterChangeEvent;
import my.side.trading.core.domain.parameter.ParameterChangeEventRepository;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FakeParameterChangeEventRepository implements ParameterChangeEventRepository {

    private final List<ParameterChangeEvent> events = new ArrayList<>();
    private long nextId = 1L;

    @Override
    public ParameterChangeEvent save(ParameterChangeEvent event) {
        ParameterChangeEvent saved = new ParameterChangeEvent(
                event.id() == null ? nextId++ : event.id(),
                event.key(),
                event.previousValue(),
                event.newValue(),
                event.requestedBy(),
                event.reason(),
                event.status(),
                event.basis(),
                event.validationMethod(),
                event.validationSummary(),
                event.nextReviewDate(),
                event.relatedArtifacts(),
                event.createdAt());
        events.add(saved);
        return saved;
    }

    @Override
    public List<ParameterChangeEvent> findRecentByKey(ParameterRegistryKey key, int limit) {
        return events.stream()
                .filter(event -> event.key() == key)
                .sorted(Comparator.comparing(ParameterChangeEvent::createdAt).reversed()
                        .thenComparing(ParameterChangeEvent::id, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    public List<ParameterChangeEvent> findAll() {
        return List.copyOf(events);
    }
}
