package my.side.trading.adapter.out.kis.account;

import my.side.trading.adapter.out.kis.client.KisOverseasPeriodProfitService;
import my.side.trading.adapter.out.kis.dto.KisOverseasPeriodProfitResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KisBrokerDailyPerformanceReaderTest {

    @Test
    void 실응답_필드명으로_일자별_손익과_수수료를_합산한다() {
        KisOverseasPeriodProfitService service = mock(KisOverseasPeriodProfitService.class);
        when(service.getPeriodProfit(LocalDate.of(2025, 12, 18), LocalDate.of(2026, 2, 4)))
                .thenReturn(new KisOverseasPeriodProfitResponse(
                        "0",
                        "KIOK0510",
                        "ok",
                        "",
                        "",
                        List.of(
                                new KisOverseasPeriodProfitResponse.Item("20260204", "-4.40000", "0.5400", "1449.00000000"),
                                new KisOverseasPeriodProfitResponse.Item("20260204", "1.00000", "0.1000", "1449.00000000"),
                                new KisOverseasPeriodProfitResponse.Item("20251218", "-3.95500", "0.0000", "1478.60000000")
                        ),
                        null
                ));

        KisBrokerDailyPerformanceReader reader = new KisBrokerDailyPerformanceReader(service);

        List<my.side.trading.core.application.port.out.BrokerDailyPerformanceReader.BrokerDailyPerformance> result =
                reader.readDailyPerformances(LocalDate.of(2025, 12, 18), LocalDate.of(2026, 2, 4));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).date()).isEqualTo(LocalDate.of(2025, 12, 18));
        assertThat(result.get(0).realizedPnlUsd()).isEqualByComparingTo("-3.95500");
        assertThat(result.get(0).brokerFeeUsd()).isEqualByComparingTo("0.0000");
        assertThat(result.get(0).taxUsd()).isEqualByComparingTo("0");
        assertThat(result.get(0).fxRate()).isEqualByComparingTo("1478.60000000");

        assertThat(result.get(1).date()).isEqualTo(LocalDate.of(2026, 2, 4));
        assertThat(result.get(1).realizedPnlUsd()).isEqualByComparingTo("-3.40000");
        assertThat(result.get(1).brokerFeeUsd()).isEqualByComparingTo("0.6400");
        assertThat(result.get(1).taxUsd()).isEqualByComparingTo("0");
        assertThat(result.get(1).fxRate()).isEqualByComparingTo("1449.00000000");
    }
}
