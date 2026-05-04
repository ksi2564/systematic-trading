package my.side.trading.adapter.out.operation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.domain.operation.OpsAlertSeverity;
import my.side.trading.core.domain.operation.OpsAlertType;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DiscordOpsAlertPublisherTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void error만_discord로_전송하고_warn은_전송하지_않는다() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, 204, "");
        });

        DiscordOpsAlertPublisher publisher = new DiscordOpsAlertPublisher(
                operationProps(serverUrl(), OpsAlertSeverity.ERROR),
                WebClient.builder().build());

        publisher.publish(new OpsAlert(
                OpsAlertType.UNRESOLVED_ORDER,
                OpsAlertSeverity.WARN,
                "warn-key",
                "warn message",
                Map.of()));
        publisher.publish(new OpsAlert(
                OpsAlertType.EOD_FAILURE,
                OpsAlertSeverity.ERROR,
                "error-key",
                "error message",
                Map.of("marketDate", "2026-04-02")));

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void discord_payload에_핵심_필드가_포함된다() throws IOException {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        startServer(exchange -> {
            bodyRef.set(readBody(exchange));
            respond(exchange, 204, "");
        });

        DiscordOpsAlertPublisher publisher = new DiscordOpsAlertPublisher(
                operationProps(serverUrl(), OpsAlertSeverity.ERROR),
                WebClient.builder().build());

        publisher.publish(new OpsAlert(
                OpsAlertType.KPI_BREACH,
                OpsAlertSeverity.ERROR,
                "kpi-breach:2026-04-02",
                "Automated execution blocked by KPI breach",
                Map.of("mode", "AUTO_LIVE", "triggerType", "AUTOMATED")));

        assertThat(bodyRef.get()).contains("[ERROR] KPI_BREACH");
        assertThat(bodyRef.get()).contains("Automated execution blocked by KPI breach");
        assertThat(bodyRef.get()).contains("kpi-breach:2026-04-02");
        assertThat(bodyRef.get()).contains("triggerType=AUTOMATED");
    }

    @Test
    void discord_전송_실패는_예외를_전파하지_않는다() throws IOException {
        startServer(exchange -> respond(exchange, 500, "boom"));

        DiscordOpsAlertPublisher publisher = new DiscordOpsAlertPublisher(
                operationProps(serverUrl(), OpsAlertSeverity.ERROR),
                WebClient.builder().build());

        assertThatCode(() -> publisher.publish(new OpsAlert(
                OpsAlertType.BROKER_API_FAILURE,
                OpsAlertSeverity.ERROR,
                "broker-failure",
                "Broker order API call failed",
                Map.of("symbol", "QQQ"))))
                .doesNotThrowAnyException();
    }

    private TradingOperationProps operationProps(String webhookUrl, OpsAlertSeverity minSeverity) {
        return new TradingOperationProps(
                OperatingMode.AUTO_LIVE,
                new TradingOperationProps.AutoLiveGateProps(5, true, true, true),
                new TradingOperationProps.KpiProps(true, 0, 0, new BigDecimal("5.0")),
                new TradingOperationProps.RiskLimitProps(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new TradingOperationProps.AlertsProps(
                        true,
                        30,
                        new TradingOperationProps.DiscordProps(true, webhookUrl, minSeverity, null, null)));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String serverUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
