package my.side.trading.adapter.in.web.kis.diagnostics;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.client.KisAuthService;
import my.side.trading.adapter.out.kis.client.KisOrderTrIdResolver;
import my.side.trading.adapter.out.kis.client.KisOverseasOrderApiSpec;
import my.side.trading.adapter.out.kis.dto.KisTokenDiagnosticsResult;
import my.side.trading.adapter.out.kis.dto.KisTokenDiagnosticsSnapshot;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.adapter.out.kis.mapper.KisOverseasOrderRequestMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@Profile("(dev | local) & !prod")
@RequiredArgsConstructor
public class KisDevDiagnosticsService {

    private final KisOverseasOrderRequestMapper orderRequestMapper;
    private final KisOrderTrIdResolver trIdResolver;
    private final KisAuthService kisAuthService;

    public KisDevOrderPayloadResponse buildOrderPayload(KisDevOrderPayloadRequest request) {
        OverseasOrderRequest body = orderRequestMapper.toRequest(
                request.normalizedSymbol(),
                request.quantity(),
                request.limitPrice()
        );

        return new KisDevOrderPayloadResponse(
                KisOverseasOrderApiSpec.OVERSEAS_ORDER_PATH,
                trIdResolver.resolveUsOrderTrId(request.side()),
                request.side(),
                body,
                false
        );
    }

    public KisTokenDiagnosticsSnapshot inspectTokenCache() {
        return kisAuthService.inspectAccessTokenCache();
    }

    public KisTokenDiagnosticsResult refreshToken() {
        return kisAuthService.ensureAccessTokenForDiagnostics();
    }

    public KisApprovalKeyDiagnosticsResponse refreshApprovalKey() {
        String approvalKey = kisAuthService.issueApprovalKey();
        return new KisApprovalKeyDiagnosticsResponse(
                approvalKey != null && !approvalKey.isBlank(),
                approvalKey == null ? 0 : approvalKey.length(),
                fingerprint(approvalKey),
                false
        );
    }

    private String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest를 사용할 수 없습니다.", e);
        }
    }
}
