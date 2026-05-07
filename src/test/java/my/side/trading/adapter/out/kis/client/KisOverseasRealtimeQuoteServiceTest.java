package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.realtime.KisRealtimeMessageHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KisOverseasRealtimeQuoteServiceTest {

    @Test
    void kis_주간세션이면_R_주간시장코드_trKey를_생성한다() throws Exception {
        KisOverseasRealtimeQuoteService service = serviceAt("2026-05-06T21:30:00Z");

        String trKey = buildUsTrKey(service, "QQQ", "NAS");

        assertThat(trKey).isEqualTo("RBAQQQQ");
    }

    @Test
    void kis_야간세션이면_D_거래소코드_trKey를_생성한다() throws Exception {
        KisOverseasRealtimeQuoteService service = serviceAt("2026-05-06T16:00:00Z");

        String trKey = buildUsTrKey(service, "QQQ", "NAS");

        assertThat(trKey).isEqualTo("DNASQQQ");
    }

    private KisOverseasRealtimeQuoteService serviceAt(String instant) {
        return new KisOverseasRealtimeQuoteService(
                mock(ReactorNettyWebSocketClient.class),
                mock(KisAuthService.class),
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private String buildUsTrKey(KisOverseasRealtimeQuoteService service, String symbol, String excd) throws Exception {
        Method method = KisOverseasRealtimeQuoteService.class.getDeclaredMethod(
                "buildUsTrKey",
                String.class,
                String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, symbol, excd);
    }
}
