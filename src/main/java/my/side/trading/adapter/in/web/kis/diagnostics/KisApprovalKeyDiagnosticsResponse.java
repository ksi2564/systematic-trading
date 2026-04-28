package my.side.trading.adapter.in.web.kis.diagnostics;

public record KisApprovalKeyDiagnosticsResponse(
        boolean issued,
        int approvalKeyLength,
        String fingerprint,
        boolean rawApprovalKeyReturned
) {
}
