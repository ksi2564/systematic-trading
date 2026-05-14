package my.side.trading.adapter.out.operation;

import lombok.extern.slf4j.Slf4j;
import my.side.trading.core.domain.operation.OpsAlert;
import my.side.trading.core.infrastructure.config.TradingOperationProps;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DiscordOpsAlertPublisher implements OpsAlertChannelPublisher {

    private final TradingOperationProps operationProps;
    private final WebClient webClient;

    public DiscordOpsAlertPublisher(
            TradingOperationProps operationProps,
            @Qualifier("discordWebClient") WebClient webClient) {
        this.operationProps = operationProps;
        this.webClient = webClient;
    }

    @Override
    public void publish(OpsAlert alert) {
        TradingOperationProps.DiscordProps discord = operationProps.alerts().discord();
        if (!discord.enabled()) {
            return;
        }
        if (discord.webhookUrl().isBlank()) {
            return;
        }
        if (!alert.severity().isAtLeast(discord.minSeverity())) {
            return;
        }

        try {
            webClient.post()
                    .uri(discord.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toPayload(alert))
                    .retrieve()
                    .toBodilessEntity()
                    .block(discord.requestTimeout());
        } catch (Exception e) {
            log.warn("discord ops alert 전송에 실패했습니다: type={}, reason={}",
                    alert.type(),
                    SensitiveDataSanitizer.sanitizeThrowable(e));
        }
    }

    private DiscordWebhookPayload toPayload(OpsAlert alert) {
        var sanitizedDetails = SensitiveDataSanitizer.sanitizeMap(alert.details());
        String details = alert.details().isEmpty()
                ? "-"
                : sanitizedDetails.entrySet().stream()
                .limit(8)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("-");

        return new DiscordWebhookPayload(
                "Trading ops alert",
                List.of(new DiscordEmbed(
                        "[%s] %s".formatted(alert.severity(), alert.type()),
                        SensitiveDataSanitizer.sanitize(alert.message()),
                        15158332,
                        List.of(
                                new DiscordEmbedField("dedupeKey", SensitiveDataSanitizer.sanitize(alert.dedupeKey()), false),
                                new DiscordEmbedField("details", details, false)))));
    }

    record DiscordWebhookPayload(
            String content,
            List<DiscordEmbed> embeds
    ) {
    }

    record DiscordEmbed(
            String title,
            String description,
            int color,
            List<DiscordEmbedField> fields
    ) {
    }

    record DiscordEmbedField(
            String name,
            String value,
            boolean inline
    ) {
    }
}
