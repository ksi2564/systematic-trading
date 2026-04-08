package my.side.trading.adapter.in.web.publicapi;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.in.web.publicapi.dto.PublicPortfolioPerformanceResponse;
import my.side.trading.adapter.in.web.publicapi.dto.PublicPortfolioSummaryResponse;
import my.side.trading.core.adapter.in.web.common.ApiResponse;
import my.side.trading.core.application.portfolio.PublicPortfolioReadService;
import my.side.trading.core.infrastructure.config.TradingSecurityProps;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
@RequestMapping("/public/api/v1")
public class PublicPortfolioController {

    private final PublicPortfolioReadService publicPortfolioReadService;
    private final TradingSecurityProps securityProps;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<PublicPortfolioSummaryResponse>> getSummary() {
        return ResponseEntity.ok()
                .cacheControl(cacheControl())
                .body(ApiResponse.success(PublicPortfolioSummaryResponse.from(publicPortfolioReadService.getSummary())));
    }

    @GetMapping("/performance")
    public ResponseEntity<ApiResponse<PublicPortfolioPerformanceResponse>> getPerformance(
            @RequestParam(defaultValue = "180") int dailyLimit
    ) {
        return ResponseEntity.ok()
                .cacheControl(cacheControl())
                .body(ApiResponse.success(PublicPortfolioPerformanceResponse.from(
                        publicPortfolioReadService.getPerformance(dailyLimit))));
    }

    private CacheControl cacheControl() {
        return CacheControl.maxAge(securityProps.publicCacheMaxAgeSeconds(), TimeUnit.SECONDS)
                .cachePublic();
    }
}
