package my.side.trading.adapter.out.calendar;

import my.side.trading.core.domain.time.MarketStatus;
import my.side.trading.core.infrastructure.config.TradingMarketCalendarProps;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigDrivenMarketStatusReaderTest {

    @Test
    void 주말은_설정이_없어도_휴장으로_판단한다() {
        ConfigDrivenMarketStatusReader reader = new ConfigDrivenMarketStatusReader(props());

        MarketStatus status = reader.getMarketStatus(LocalDate.of(2026, 4, 4));

        assertThat(status).isEqualTo(MarketStatus.HOLIDAY);
    }

    @Test
    void 조기폐장일은_early_close로_판단한다() {
        LocalDate earlyCloseDate = LocalDate.of(2026, 11, 27);
        ConfigDrivenMarketStatusReader reader = new ConfigDrivenMarketStatusReader(props(
                List.of(),
                List.of(earlyCloseDate),
                List.of()));

        MarketStatus status = reader.getMarketStatus(earlyCloseDate);

        assertThat(status).isEqualTo(MarketStatus.EARLY_CLOSE);
    }

    @Test
    void 데이터_미확정일이_우선한다() {
        LocalDate uncertainDate = LocalDate.of(2026, 7, 3);
        ConfigDrivenMarketStatusReader reader = new ConfigDrivenMarketStatusReader(props(
                List.of(uncertainDate),
                List.of(uncertainDate),
                List.of(uncertainDate)));

        MarketStatus status = reader.getMarketStatus(uncertainDate);

        assertThat(status).isEqualTo(MarketStatus.DATA_UNCERTAIN);
    }

    private TradingMarketCalendarProps props() {
        return props(List.of(), List.of(), List.of());
    }

    private TradingMarketCalendarProps props(List<LocalDate> holidays,
                                             List<LocalDate> earlyCloses,
                                             List<LocalDate> dataUncertainDates) {
        return new TradingMarketCalendarProps(
                "America/New_York",
                holidays,
                earlyCloses,
                dataUncertainDates);
    }
}
