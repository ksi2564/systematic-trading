package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.PortfolioSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortfolioSnapshotJpaRepository extends JpaRepository<PortfolioSnapshotEntity, Long> {
    Optional<PortfolioSnapshotEntity> findTopByAsOfDateOrderByIdDesc(LocalDate asOfDate);

    Optional<PortfolioSnapshotEntity> findTopByOrderByAsOfDateDescIdDesc();

    List<PortfolioSnapshotEntity> findAllByOrderByAsOfDateAscIdAsc();
}
