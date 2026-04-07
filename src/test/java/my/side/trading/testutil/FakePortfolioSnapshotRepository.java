package my.side.trading.testutil;

import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class FakePortfolioSnapshotRepository implements PortfolioSnapshotRepository {

    private final List<PortfolioSnapshot> snapshots = new ArrayList<>();

    @Override
    public PortfolioSnapshot save(PortfolioSnapshot snapshot) {
        snapshots.removeIf(existing -> existing.asOfDate().isEqual(snapshot.asOfDate()));
        snapshots.add(snapshot);
        snapshots.sort(Comparator.comparing(PortfolioSnapshot::asOfDate));
        return snapshot;
    }

    @Override
    public Optional<PortfolioSnapshot> findLatest() {
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshots.getLast());
    }

    @Override
    public Optional<PortfolioSnapshot> findByAsOfDate(LocalDate asOfDate) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.asOfDate().isEqual(asOfDate))
                .findFirst();
    }

    @Override
    public List<PortfolioSnapshot> findAllOrderByAsOfDateAsc() {
        return List.copyOf(snapshots);
    }
}
