package my.side.trading.core.domain.portfolio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortfolioSnapshotRepository {
    PortfolioSnapshot save(PortfolioSnapshot snapshot);

    Optional<PortfolioSnapshot> findLatest();

    Optional<PortfolioSnapshot> findByAsOfDate(LocalDate asOfDate);

    List<PortfolioSnapshot> findAllOrderByAsOfDateAsc();
}
