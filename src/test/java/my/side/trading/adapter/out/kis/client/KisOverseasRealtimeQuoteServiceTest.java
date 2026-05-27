package my.side.trading.adapter.out.kis.client;

import my.side.trading.adapter.out.kis.dto.RealtimeQuoteSubscriptionResult;
import my.side.trading.adapter.out.kis.dto.SymbolTarget;
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
import static org.mockito.Mockito.times;
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
                .thenReturn(Mono.<Void>never().doOnSubscribe(s -> subscribed.set(true)));

        KisOverseasRealtimeQuoteService service = new KisOverseasRealtimeQuoteService(
                wsClient,
                authService,
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse("2026-05-06T16:00:00Z"), ZoneOffset.UTC));

        RealtimeQuoteSubscriptionResult result = service.subscribeRealtimeQuote("QQQM", "NAS");

        assertThat(subscribed).isTrue();
        assertThat(result.status()).isEqualTo("SUBSCRIBED");
        assertThat(result.activeTargets()).containsExactly(new SymbolTarget("QQQM", "NAS"));
        verify(authService).issueApprovalKey();
    }

    @Test
    void 같은_종목_구독을_반복하면_WebSocket_연결을_추가로_만들지_않는다() {
        ReactorNettyWebSocketClient wsClient = mock(ReactorNettyWebSocketClient.class);
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.issueApprovalKey()).thenReturn("approval-key");
        when(wsClient.execute(any(URI.class), any())).thenReturn(Mono.<Void>never());

        KisOverseasRealtimeQuoteService service = new KisOverseasRealtimeQuoteService(
                wsClient,
                authService,
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse("2026-05-06T16:00:00Z"), ZoneOffset.UTC));

        RealtimeQuoteSubscriptionResult first = service.subscribeRealtimeQuote("QQQM", "NAS");
        RealtimeQuoteSubscriptionResult second = service.subscribeRealtimeQuote("QQQM", "NAS");

        assertThat(first.status()).isEqualTo("SUBSCRIBED");
        assertThat(second.status()).isEqualTo("ALREADY_SUBSCRIBED");
        assertThat(second.targetAdded()).isFalse();
        assertThat(second.connectionRestarted()).isFalse();
        assertThat(second.activeTargets()).containsExactly(new SymbolTarget("QQQM", "NAS"));
        verify(wsClient, times(1)).execute(any(URI.class), any());
        verify(authService, times(1)).issueApprovalKey();
    }

    @Test
    void core_ETF_구독을_반복해도_WebSocket_연결은_하나만_유지한다() {
        ReactorNettyWebSocketClient wsClient = mock(ReactorNettyWebSocketClient.class);
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.issueApprovalKey()).thenReturn("approval-key");
        when(wsClient.execute(any(URI.class), any())).thenReturn(Mono.<Void>never());

        KisOverseasRealtimeQuoteService service = new KisOverseasRealtimeQuoteService(
                wsClient,
                authService,
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse("2026-05-06T16:00:00Z"), ZoneOffset.UTC));

        service.startCoreEtfRealtimeQuotes();
        service.startCoreEtfRealtimeQuotes();

        verify(wsClient, times(1)).execute(any(URI.class), any());
        verify(authService, times(1)).issueApprovalKey();
    }

    @Test
    void 새_종목을_추가하면_기존_관리형_stream을_전체_구독으로_재시작한다() {
        ReactorNettyWebSocketClient wsClient = mock(ReactorNettyWebSocketClient.class);
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.issueApprovalKey()).thenReturn("approval-key");
        when(wsClient.execute(any(URI.class), any())).thenReturn(Mono.<Void>never());

        KisOverseasRealtimeQuoteService service = new KisOverseasRealtimeQuoteService(
                wsClient,
                authService,
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse("2026-05-06T16:00:00Z"), ZoneOffset.UTC));

        service.subscribeRealtimeQuote("QQQM", "NAS");
        RealtimeQuoteSubscriptionResult result = service.subscribeRealtimeQuote("QLD", "AMS");

        assertThat(result.status()).isEqualTo("RESTARTED");
        assertThat(result.targetAdded()).isTrue();
        assertThat(result.connectionRestarted()).isTrue();
        assertThat(result.activeTargets()).containsExactly(
                new SymbolTarget("QQQM", "NAS"),
                new SymbolTarget("QLD", "AMS")
        );
        verify(wsClient, times(2)).execute(any(URI.class), any());
        verify(authService, times(2)).issueApprovalKey();
    }

    @Test
    void 종목과_거래소코드는_공백을_제거하고_대문자로_정규화한다() {
        ReactorNettyWebSocketClient wsClient = mock(ReactorNettyWebSocketClient.class);
        KisAuthService authService = mock(KisAuthService.class);
        when(authService.issueApprovalKey()).thenReturn("approval-key");
        when(wsClient.execute(any(URI.class), any())).thenReturn(Mono.<Void>never());

        KisOverseasRealtimeQuoteService service = new KisOverseasRealtimeQuoteService(
                wsClient,
                authService,
                mock(KisRealtimeMessageHandler.class),
                Clock.fixed(Instant.parse("2026-05-06T16:00:00Z"), ZoneOffset.UTC));

        RealtimeQuoteSubscriptionResult result = service.subscribeRealtimeQuote(" qqqm ", " nas ");

        assertThat(result.symbol()).isEqualTo("QQQM");
        assertThat(result.excd()).isEqualTo("NAS");
        assertThat(result.activeTargets()).containsExactly(new SymbolTarget("QQQM", "NAS"));
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
