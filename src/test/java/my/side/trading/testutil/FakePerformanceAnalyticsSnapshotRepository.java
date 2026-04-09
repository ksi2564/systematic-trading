package my.side.trading.testutil;

import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshotRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class FakePerformanceAnalyticsSnapshotRepository implements PerformanceAnalyticsSnapshotRepository {

    private final List<PerformanceAnalyticsSnapshot> snapshots = new ArrayList<>();

    @Override
    public PerformanceAnalyticsSnapshot save(PerformanceAnalyticsSnapshot snapshot) {
        snapshots.removeIf(existing -> existing.asOfDate().isEqual(snapshot.asOfDate()));
        snapshots.add(snapshot);
        snapshots.sort(Comparator.comparing(PerformanceAnalyticsSnapshot::asOfDate));
        return snapshot;
    }

    @Override
    public Optional<PerformanceAnalyticsSnapshot> findByAsOfDate(LocalDate asOfDate) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.asOfDate().isEqual(asOfDate))
                .findFirst();
    }

    @Override
    public List<PerformanceAnalyticsSnapshot> findAllOrderByAsOfDateAsc() {
        return List.copyOf(snapshots);
    }

    @Override
    public List<PerformanceAnalyticsSnapshot> findAllBetweenOrderByAsOfDateAsc(LocalDate startDate, LocalDate endDate) {
        return snapshots.stream()
                .filter(snapshot -> !snapshot.asOfDate().isBefore(startDate) && !snapshot.asOfDate().isAfter(endDate))
                .sorted(Comparator.comparing(PerformanceAnalyticsSnapshot::asOfDate))
                .toList();
    }
}
