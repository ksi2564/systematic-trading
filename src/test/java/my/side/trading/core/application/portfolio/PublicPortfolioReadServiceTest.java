package my.side.trading.core.application.portfolio;

import my.side.trading.core.domain.portfolio.PortfolioSnapshot;
import my.side.trading.testutil.FakePortfolioSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPortfolioReadServiceTest {

    @Test
    void 스냅샷이없으면_요약은_unavailable을반환한다() {
        PublicPortfolioReadService service = new PublicPortfolioReadService(new FakePortfolioSnapshotRepository());

        PublicPortfolioSummaryView summary = service.getSummary();
        PublicPortfolioPerformanceView performance = service.getPerformance(180);

        assertThat(summary.dataAvailable()).isFalse();
        assertThat(summary.recentMonthlyReturnPcts()).isEmpty();
        assertThat(performance.baseIndex()).isEqualByComparingTo("100.0000");
        assertThat(performance.recentDailyIndexSeries()).isEmpty();
    }

    @Test
    void 요약은_비중과_누적수익률을_비율중심으로_계산한다() {
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        repository.save(snapshot("2026-03-31", "1000", "100", "90", "10", "0", "5"));
        repository.save(snapshot("2026-04-01", "1100", "220", "70", "20", "10", "3"));
        PublicPortfolioReadService service = new PublicPortfolioReadService(repository);

        PublicPortfolioSummaryView summary = service.getSummary();

        assertThat(summary.dataAvailable()).isTrue();
        assertThat(summary.asOfDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(summary.cumulativeReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(summary.holdingWeights().qqqPct()).isEqualByComparingTo("70");
        assertThat(summary.holdingWeights().cashPct()).isEqualByComparingTo("20.0000");
        assertThat(summary.recentMonthlyReturnPcts()).hasSize(2);
        assertThat(summary.recentMonthlyReturnPcts().getLast().returnPct()).isEqualByComparingTo("0.0000");
    }

    @Test
    void 성과는_정규화인덱스와_월별수익률시계열을_반환한다() {
        FakePortfolioSnapshotRepository repository = new FakePortfolioSnapshotRepository();
        repository.save(snapshot("2026-04-01", "1000", "100", "90", "10", "0", "5"));
        repository.save(snapshot("2026-04-02", "1100", "110", "80", "20", "0", "3"));
        repository.save(snapshot("2026-04-03", "1210", "121", "70", "20", "10", "1"));
        PublicPortfolioReadService service = new PublicPortfolioReadService(repository);

        PublicPortfolioPerformanceView performance = service.getPerformance(2);

        assertThat(performance.baseIndex()).isEqualByComparingTo("100.0000");
        assertThat(performance.recentDailyIndexSeries()).hasSize(2);
        assertThat(performance.recentDailyIndexSeries().getFirst().index()).isEqualByComparingTo("100.0000");
        assertThat(performance.recentDailyIndexSeries().getLast().index()).isEqualByComparingTo("110.0000");
        assertThat(performance.monthlyReturnSeries()).singleElement()
                .satisfies(monthly -> assertThat(monthly.returnPct()).isEqualByComparingTo("21.0000"));
    }

    private PortfolioSnapshot snapshot(
            String asOfDate,
            String totalValue,
            String cash,
            String wQqq,
            String wQld,
            String wTqqq,
            String ddPercent
    ) {
        return new PortfolioSnapshot(
                LocalDate.parse(asOfDate),
                new BigDecimal(totalValue),
                new BigDecimal(cash),
                new BigDecimal(wQqq),
                new BigDecimal(wQld),
                new BigDecimal(wTqqq),
                new BigDecimal(ddPercent)
        );
    }
}
