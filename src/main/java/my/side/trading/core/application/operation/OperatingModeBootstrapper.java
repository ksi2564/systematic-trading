package my.side.trading.core.application.operation;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OperatingModeBootstrapper {

    private final OperatingModeService operatingModeService;

    @PostConstruct
    void initialize() {
        operatingModeService.initializeIfMissing();
    }
}
