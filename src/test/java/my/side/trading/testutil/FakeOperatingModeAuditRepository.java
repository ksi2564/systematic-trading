package my.side.trading.testutil;

import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeAuditRepository;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class FakeOperatingModeAuditRepository implements OperatingModeAuditRepository {

    private final AtomicLong sequence = new AtomicLong(1);
    private final List<OperatingModeAuditEvent> store = new ArrayList<>();

    @Override
    public OperatingModeAuditEvent save(OperatingModeAuditEvent event) {
        OperatingModeAuditEvent saved = new OperatingModeAuditEvent(
                event.id() == null ? sequence.getAndIncrement() : event.id(),
                event.previousMode(),
                event.targetMode(),
                event.transitionType(),
                event.triggerSource(),
                event.triggerCode(),
                event.requestedBy(),
                event.reason(),
                event.approvedBy(),
                event.approvedAt(),
                event.createdAt());
        store.add(saved);
        return saved;
    }

    @Override
    public List<OperatingModeAuditEvent> findRecent(int limit) {
        return store.stream()
                .sorted(Comparator.comparing(OperatingModeAuditEvent::createdAt)
                        .thenComparing(OperatingModeAuditEvent::id)
                        .reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public boolean hasManualAutoLiveApproval() {
        return store.stream().anyMatch(event ->
                event.targetMode() == OperatingMode.AUTO_LIVE
                        && event.triggerSource() == OperatingModeTriggerSource.MANUAL_API
                        && event.approvedAt() != null);
    }

    public List<OperatingModeAuditEvent> findAll() {
        return List.copyOf(store);
    }
}
