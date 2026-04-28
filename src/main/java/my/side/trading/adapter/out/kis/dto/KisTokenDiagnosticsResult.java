package my.side.trading.adapter.out.kis.dto;

public record KisTokenDiagnosticsResult(
        boolean available,
        String source,
        KisTokenDiagnosticsSnapshot token
) {
}
