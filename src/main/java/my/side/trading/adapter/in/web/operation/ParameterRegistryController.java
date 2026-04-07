package my.side.trading.adapter.in.web.operation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.in.web.operation.dto.ParameterChangeHistoryResponse;
import my.side.trading.adapter.in.web.operation.dto.ParameterRegistryResponse;
import my.side.trading.adapter.in.web.operation.dto.RecordParameterChangeRequest;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.operation.ParameterRegistryService;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/operations/parameter-registry")
public class ParameterRegistryController {

    private final ParameterRegistryService parameterRegistryService;

    @GetMapping
    public ApiResponse<List<ParameterRegistryResponse>> getRegistry() {
        return ApiResponse.success(parameterRegistryService.getRegistry().stream()
                .map(ParameterRegistryResponse::from)
                .toList());
    }

    @GetMapping("/history")
    public ApiResponse<List<ParameterChangeHistoryResponse>> getHistory(
            @RequestParam ParameterRegistryKey key,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.success(parameterRegistryService.getHistory(key, limit).stream()
                .map(ParameterChangeHistoryResponse::from)
                .toList());
    }

    @PostMapping("/history")
    public ApiResponse<ParameterRegistryResponse> recordHistory(
            @Valid @RequestBody RecordParameterChangeRequest request
    ) {
        return ApiResponse.success(ParameterRegistryResponse.from(
                parameterRegistryService.recordChange(request.toCommand())));
    }
}
