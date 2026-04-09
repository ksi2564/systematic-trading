package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.entity.PerformanceAnalyticsSnapshotEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.PerformanceAnalyticsSnapshotJpaRepository;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshot;
import my.side.trading.core.domain.portfolio.PerformanceAnalyticsSnapshotRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PerformanceAnalyticsSnapshotRepositoryImpl implements PerformanceAnalyticsSnapshotRepository {

    private final PerformanceAnalyticsSnapshotJpaRepository jpaRepository;

    @Override
    @Transactional
    public PerformanceAnalyticsSnapshot save(PerformanceAnalyticsSnapshot snapshot) {
        Long existingId = jpaRepository.findTopByAsOfDateOrderByIdDesc(snapshot.asOfDate())
                .map(PerformanceAnalyticsSnapshotEntity::getId)
                .orElse(null);
        PerformanceAnalyticsSnapshotEntity saved = jpaRepository.save(
                PerformanceAnalyticsSnapshotEntity.from(existingId, snapshot)
        );
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PerformanceAnalyticsSnapshot> findByAsOfDate(LocalDate asOfDate) {
        return jpaRepository.findTopByAsOfDateOrderByIdDesc(asOfDate)
                .map(PerformanceAnalyticsSnapshotEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PerformanceAnalyticsSnapshot> findAllOrderByAsOfDateAsc() {
        return jpaRepository.findAllByOrderByAsOfDateAscIdAsc().stream()
                .map(PerformanceAnalyticsSnapshotEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PerformanceAnalyticsSnapshot> findAllBetweenOrderByAsOfDateAsc(LocalDate startDate, LocalDate endDate) {
        return jpaRepository.findAllByAsOfDateBetweenOrderByAsOfDateAscIdAsc(startDate, endDate).stream()
                .map(PerformanceAnalyticsSnapshotEntity::toDomain)
                .toList();
    }
}
