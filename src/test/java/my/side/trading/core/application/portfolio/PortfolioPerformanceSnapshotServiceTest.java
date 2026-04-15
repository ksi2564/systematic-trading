package my.side.trading.core.application.portfolio;

import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.testutil.FakePortfolioSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioPerformanceSnapshotServiceTest {

    @Test
    void 첫_스냅샷을_저장한다() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        PortfolioPerformanceSnapshotService service = new PortfolioPerformanceSnapshotService(portfolioService, repository);

        when(portfolioService.getCurrentPortfolio()).thenReturn(portfolio(
                "1000.0000",
                new Position("QQQ", new BigDecimal("1"), new BigDecimal("100"), new BigDecimal("100"))
        ));

        PortfolioSnapshot snapshot = service.captureDailySnapshot(LocalDate.of(2026, 4, 7));

        assertThat(snapshot.totalValue()).isEqualByComparingTo("1100.0000");
        assertThat(snapshot.ddPercent()).isEqualByComparingTo("0.0000");
        assertThat(repository.findAllOrderByAsOfDateAsc()).hasSize(1);
    }

    @Test
    void 동일일자_스냅샷을_덮어쓰고_고점_nav_기준_낙폭을_재계산한다() {
        PortfolioService portfolioService = mock(PortfolioService.class);
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        PortfolioPerformanceSnapshotService service = new PortfolioPerformanceSnapshotService(portfolioService, repository);

        repository.save(snapshot(LocalDate.of(2026, 4, 6), "1200.0000", "0.0000"));

        when(portfolioService.getCurrentPortfolio()).thenReturn(portfolio(
                "900.0000",
                new Position("QQQ", new BigDecimal("1"), new BigDecimal("100"), new BigDecimal("100"))
        ));
        service.captureDailySnapshot(LocalDate.of(2026, 4, 7));

        when(portfolioService.getCurrentPortfolio()).thenReturn(portfolio(
                "700.0000",
                new Position("QQQ", new BigDecimal("1"), new BigDecimal("100"), new BigDecimal("100"))
        ));
        PortfolioSnapshot overwritten = service.captureDailySnapshot(LocalDate.of(2026, 4, 7));

        assertThat(repository.findAllOrderByAsOfDateAsc()).hasSize(2);
        assertThat(overwritten.totalValue()).isEqualByComparingTo("800.0000");
        assertThat(overwritten.ddPercent()).isEqualByComparingTo("33.3333");
    }

    private Portfolio portfolio(String cash, Position... positions) {
        return new Portfolio(new BigDecimal(cash), List.of(positions));
    }

    private PortfolioSnapshot snapshot(LocalDate asOfDate, String totalValue, String ddPercent) {
        return new PortfolioSnapshot(
                asOfDate,
                new BigDecimal(totalValue),
                new BigDecimal(totalValue),
                BigDecimal.ZERO.setScale(4),
                BigDecimal.ZERO.setScale(4),
                BigDecimal.ZERO.setScale(4),
                new BigDecimal(ddPercent)
        );
    }
}
