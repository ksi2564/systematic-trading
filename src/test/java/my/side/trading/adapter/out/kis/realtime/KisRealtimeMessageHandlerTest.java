package my.side.trading.adapter.out.kis.realtime;

import my.side.trading.adapter.out.realtime.InMemoryRealtimePriceProvider;
import my.side.trading.core.domain.portfolio.RealtimeQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KisRealtimeMessageHandlerTest {

    @Test
    void 해외주식_실시간호가_payload를_수신하면_호가_캐시를_갱신한다() {
        InMemoryRealtimePriceProvider priceProvider = new InMemoryRealtimePriceProvider();
        KisRealtimeMessageHandler handler = new KisRealtimeMessageHandler(priceProvider);

        handler.handleMessage("0|HDFSASP0|001|"
                + "RBAQQQQ^QQQ^2^20260513^093000^20260514^223000^10^20^1^2^100.12^100.14^100^110^0^0");

        RealtimeQuote quote = priceProvider.getQuote("QQQ").orElseThrow();
        assertThat(quote.bestBidPrice()).isEqualByComparingTo(new BigDecimal("100.12"));
        assertThat(quote.bestAskPrice()).isEqualByComparingTo(new BigDecimal("100.14"));
        assertThat(quote.lastPrice()).isEqualByComparingTo(new BigDecimal("100.1300"));
    }
}
