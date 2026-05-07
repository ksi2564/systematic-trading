package my.side.trading.core.application.market;

import my.side.trading.core.domain.time.MarketStatus;
import my.side.trading.core.domain.time.MarketStatusReader;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketCalendarServiceTest {

    @Test
    void 현재_시장일자는_설정된_시장_timezone과_clock으로_계산한다() {
        MarketStatusReader marketStatusReader = mock(MarketStatusReader.class);
        TradingMarketCalendarProps props = new TradingMarketCalendarProps(
                "America/New_York",
                List.of(),
                List.of(),
                List.of());
        Clock clock = Clock.fixed(Instant.parse("2026-05-08T01:00:00Z"), ZoneOffset.UTC);
        MarketCalendarService service = new MarketCalendarService(marketStatusReader, props, clock);

        LocalDate marketDate = service.currentMarketDate();

        assertThat(marketDate).isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    void 시장상태_조회는_reader에_위임한다() {
        MarketStatusReader marketStatusReader = mock(MarketStatusReader.class);
        TradingMarketCalendarProps props = new TradingMarketCalendarProps(
                "America/New_York",
                List.of(),
                List.of(),
                List.of());
        Clock clock = Clock.fixed(Instant.parse("2026-05-08T01:00:00Z"), ZoneOffset.UTC);
        MarketCalendarService service = new MarketCalendarService(marketStatusReader, props, clock);
        LocalDate marketDate = LocalDate.of(2026, 5, 7);
        when(marketStatusReader.getMarketStatus(marketDate)).thenReturn(MarketStatus.REGULAR);

        MarketStatus status = service.getMarketStatus(marketDate);

        assertThat(status).isEqualTo(MarketStatus.REGULAR);
    }
}
