package my.side.trading.core.domain.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioTest {

    @Test
    void 총자산_계산_현금과_포지션_합산() {
        Portfolio portfolio = new Portfolio(
                new BigDecimal("1000"),
                List.of(
                        new Position("QQQ", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("100")), // 1000
                        new Position("QLD", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("200")) // 1000
                ));

        BigDecimal total = portfolio.totalValue();

        assertThat(total).isEqualByComparingTo("3000");
    }

    @Test
    void 빈_포트폴리오의_총자산은_현금만() {
        Portfolio portfolio = new Portfolio(new BigDecimal("5000"), List.of());

        assertThat(portfolio.totalValue()).isEqualByComparingTo("5000");
    }

    @Test
    void 현금이_null이어도_포지션_합계_계산() {
        Portfolio portfolio = new Portfolio(
                null,
                List.of(new Position("QQQ", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("100"))));

        assertThat(portfolio.totalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void 비중_계산_정확도_검증() {
        Portfolio portfolio = new Portfolio(
                BigDecimal.ZERO,
                List.of(
                        new Position("QQQ", new BigDecimal("6"), BigDecimal.ZERO, new BigDecimal("100")), // 600
                        new Position("QLD", new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("100")), // 300
                        new Position("TQQQ", new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("100")) // 100
                ));

        assertThat(portfolio.wQqq()).isEqualByComparingTo("60.0000");
        assertThat(portfolio.wQld()).isEqualByComparingTo("30.0000");
        assertThat(portfolio.wTqqq()).isEqualByComparingTo("10.0000");
    }

    @Test
    void 총자산이_0이면_비중은_0반환() {
        Portfolio portfolio = new Portfolio(BigDecimal.ZERO, List.of());

        assertThat(portfolio.weightOf("QQQ")).isEqualByComparingTo("0");
        assertThat(portfolio.weightOf(Ticker.QQQ)).isEqualByComparingTo("0");
    }

    @Test
    void Ticker_enum으로_비중_조회() {
        Portfolio portfolio = new Portfolio(
                BigDecimal.ZERO,
                List.of(new Position("QQQ", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("100"))));

        assertThat(portfolio.weightOf(Ticker.QQQ)).isEqualByComparingTo("100.0000");
        assertThat(portfolio.weightOf(Ticker.QLD)).isEqualByComparingTo("0.0000");
    }

    @Test
    void 수량_조회_특정_종목() {
        Portfolio portfolio = new Portfolio(
                new BigDecimal("1000"),
                List.of(
                        new Position("QQQ", new BigDecimal("15"), BigDecimal.ZERO, new BigDecimal("100")),
                        new Position("QQQ", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("100")), // 동일 종목 2건
                        new Position("QLD", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("100"))));

        assertThat(portfolio.quantityOf("QQQ")).isEqualByComparingTo("20");
        assertThat(portfolio.quantityOf(Ticker.QQQ)).isEqualByComparingTo("20");
        assertThat(portfolio.quantityOf(Ticker.QLD)).isEqualByComparingTo("10");
    }
}
