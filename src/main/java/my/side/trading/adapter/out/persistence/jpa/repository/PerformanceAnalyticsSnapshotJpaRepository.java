package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.PerformanceAnalyticsSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PerformanceAnalyticsSnapshotJpaRepository extends JpaRepository<PerformanceAnalyticsSnapshotEntity, Long> {

    Optional<PerformanceAnalyticsSnapshotEntity> findTopByAsOfDateOrderByIdDesc(LocalDate asOfDate);

    List<PerformanceAnalyticsSnapshotEntity> findAllByOrderByAsOfDateAscIdAsc();

    List<PerformanceAnalyticsSnapshotEntity> findAllByAsOfDateBetweenOrderByAsOfDateAscIdAsc(LocalDate startDate, LocalDate endDate);
}
