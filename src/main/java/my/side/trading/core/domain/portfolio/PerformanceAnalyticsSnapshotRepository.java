package my.side.trading.core.domain.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PerformanceAnalyticsSnapshotRepository {

    PerformanceAnalyticsSnapshot save(PerformanceAnalyticsSnapshot snapshot);

    Optional<PerformanceAnalyticsSnapshot> findByAsOfDate(LocalDate asOfDate);

    List<PerformanceAnalyticsSnapshot> findAllOrderByAsOfDateAsc();

    List<PerformanceAnalyticsSnapshot> findAllBetweenOrderByAsOfDateAsc(LocalDate startDate, LocalDate endDate);
}
