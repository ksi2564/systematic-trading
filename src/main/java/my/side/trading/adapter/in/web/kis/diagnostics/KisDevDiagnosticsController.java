package my.side.trading.adapter.in.web.kis.diagnostics;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.dto.KisTokenDiagnosticsResult;
import my.side.trading.adapter.out.kis.dto.KisTokenDiagnosticsSnapshot;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@Profile("(dev | local) & !prod")
@RequiredArgsConstructor
@RequestMapping("/kis/dev-diagnostics")
public class KisDevDiagnosticsController {

    private final KisDevDiagnosticsService diagnosticsService;

    @PostMapping("/orders/payload")
    public ApiResponse<KisDevOrderPayloadResponse> buildOrderPayload(
            @Valid @RequestBody KisDevOrderPayloadRequest request
    ) {
        return ApiResponse.success(diagnosticsService.buildOrderPayload(request));
    }

    @GetMapping("/token")
    public ApiResponse<KisTokenDiagnosticsSnapshot> inspectTokenCache() {
        return ApiResponse.success(diagnosticsService.inspectTokenCache());
    }

    @PostMapping("/token/refresh")
    public ApiResponse<KisTokenDiagnosticsResult> refreshToken() {
        return ApiResponse.success(diagnosticsService.refreshToken());
    }

    @PostMapping("/approval-key/refresh")
    public ApiResponse<KisApprovalKeyDiagnosticsResponse> refreshApprovalKey() {
        return ApiResponse.success(diagnosticsService.refreshApprovalKey());
    }
}
