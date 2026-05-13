package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.realtime.KisRealtimeMessageHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisOverseasRealtimeQuoteServiceTest {

    @Test
    void kis_주간세션이면_R_주간시장코드_trKey를_생성한다() throws Exception {
        KisOverseasRealtimeQuoteService service = serviceAt("2026-05-06T21:30:00Z");

        String trKey = buildUsTrKey(service, "QQQM", "NAS");

        assertThat(trKey).isEqualTo("RBAQQQQM");
    }

    @Test
    void kis_야간세션이면_D_거래소코드_trKey를_생성한다() throws Exception {
        KisOverseasRealtimeQuoteService service = serviceAt("2026-05-06T16:00:00Z");

        String trKey = buildUsTrKey(service, "QQQM", "NAS");

        assertThat(trKey).isEqualTo("DNASQQQM");
    }

    @Test
    void 진단용_단일종목_구독은_WebSocket_Mono를_실제로_구독한다() {
        ReactorNettyWebSocketClient wsClient = mock(ReactorNettyWebSocketClient.class);
        KisAuthService authService = mock(KisAuthService.class);
        AtomicBoolean subscribed = new AtomicBoolean(false);
        when(authService.issueApprovalKey()).thenReturn("approval-key");
        when(wsClient.execute(any(URI.class), any()))
                .thenReturn(Mono.fromRunnable(() -> subscribed.set(true)));

        KisOverseasRealtimeQuoteService service = new KisOverseasRealtimeQuoteService(
                wsClient,
                authService,
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse("2026-05-06T16:00:00Z"), ZoneOffset.UTC));

        service.subscribeRealtimeQuote("QQQM", "NAS");

        assertThat(subscribed).isTrue();
        verify(authService).issueApprovalKey();
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
