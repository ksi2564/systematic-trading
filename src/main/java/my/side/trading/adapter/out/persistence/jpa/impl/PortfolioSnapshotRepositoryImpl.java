package my.side.trading.adapter.out.persistence.jpa.impl;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.entity.PortfolioSnapshotEntity;
import my.side.trading.adapter.out.persistence.jpa.repository.PortfolioSnapshotJpaRepository;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.core.domain.portfolio.PortfolioSnapshotRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PortfolioSnapshotRepositoryImpl implements PortfolioSnapshotRepository {

    private final PortfolioSnapshotJpaRepository jpaRepository;

    @Override
    @Transactional
    public PortfolioSnapshot save(PortfolioSnapshot snapshot) {
        Long existingId = jpaRepository.findTopByAsOfDateOrderByIdDesc(snapshot.asOfDate())
                .map(PortfolioSnapshotEntity::getId)
                .orElse(null);

        PortfolioSnapshotEntity saved = jpaRepository.save(PortfolioSnapshotEntity.from(existingId, snapshot));
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PortfolioSnapshot> findLatest() {
        return jpaRepository.findTopByOrderByAsOfDateDescIdDesc()
                .map(PortfolioSnapshotEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PortfolioSnapshot> findByAsOfDate(LocalDate asOfDate) {
        return jpaRepository.findTopByAsOfDateOrderByIdDesc(asOfDate)
                .map(PortfolioSnapshotEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PortfolioSnapshot> findAllOrderByAsOfDateAsc() {
        return jpaRepository.findAllByOrderByAsOfDateAscIdAsc().stream()
                .map(PortfolioSnapshotEntity::toDomain)
                .toList();
    }
}
