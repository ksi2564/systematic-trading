package my.side.trading.adapter.out.kis.order;

import my.side.trading.adapter.out.kis.client.KisOverseasCcnlService;
import my.side.trading.adapter.out.kis.dto.KisOverseasCcnlResponse;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.domain.execution.order.FillResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisOrderFillCheckerTest {

    @Test
    void 체결조회일은_현재_시장일자를_사용한다() {
        KisOverseasCcnlService ccnlService = mock(KisOverseasCcnlService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        KisOrderFillChecker checker = new KisOrderFillChecker(ccnlService, marketCalendarService);
        LocalDate marketDate = LocalDate.of(2026, 5, 7);
        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(ccnlService.inquireCcnl("NASD", "QQQ", marketDate))
                .thenReturn(new KisOverseasCcnlResponse(
                        "0",
                        "OK",
                        "정상",
                        List.of(new KisOverseasCcnlResponse.Item(
                                "OD123",
                                "QQQ",
                                "02",
                                "3",
                                "100",
                                "3",
                                "0"))));

        FillResult result = checker.checkFill("OD123", "QQQ");

        assertThat(result.fullyFilled()).isTrue();
        assertThat(result.filledQty()).isEqualTo(3);
        verify(ccnlService).inquireCcnl("NASD", "QQQ", marketDate);
    }
}
