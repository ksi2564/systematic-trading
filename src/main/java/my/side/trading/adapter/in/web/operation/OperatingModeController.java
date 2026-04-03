package my.side.trading.adapter.in.web.operation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.in.web.operation.dto.OperatingModeChangeRequest;
import my.side.trading.adapter.in.web.operation.dto.OperatingModeStatusResponse;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/operations")
public class OperatingModeController {

    private final OperatingModeService operatingModeService;

    @GetMapping("/mode")
    public ApiResponse<OperatingModeStatusResponse> getCurrentMode() {
        return ApiResponse.success(OperatingModeStatusResponse.fromStatus(
                operatingModeService.getCurrentStatus(5)));
    }

    @PostMapping("/mode")
    public ApiResponse<OperatingModeStatusResponse> changeMode(
            @Valid @RequestBody OperatingModeChangeRequest request
    ) {
        var result = operatingModeService.changeMode(
                request.targetMode(),
                request.requestedBy(),
                request.reason());
        return ApiResponse.success(OperatingModeStatusResponse.fromChangeResult(
                result,
                operatingModeService.recentHistory(5)));
    }

    @GetMapping("/mode-history")
    public ApiResponse<List<OperatingModeAuditEvent>> getModeHistory(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.success(operatingModeService.recentHistory(limit));
    }
}
