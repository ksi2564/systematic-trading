package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.dto.KisDailyChartPriceResponse;
import my.side.trading.core.infrastructure.config.TradingPerformanceProps;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class KisDailyFxRateService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final WebClient kisWebClient;
    private final KisAuthService kisAuthService;
    private final TradingPerformanceProps performanceProps;

    public KisDailyChartPriceResponse getUsdKrwRates(LocalDate startDate, LocalDate endDate) {
        String accessToken = kisAuthService.getAccessToken();

        return kisWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/overseas-price/v1/quotations/inquire-daily-chartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "X")
                        .queryParam("FID_INPUT_ISCD", performanceProps.fx().usdKrwSymbol())
                        .queryParam("FID_INPUT_DATE_1", startDate.format(YYYYMMDD))
                        .queryParam("FID_INPUT_DATE_2", endDate.format(YYYYMMDD))
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build())
                .header("authorization", "Bearer " + accessToken)
                .header("tr_id", "FHKST03030100")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisDailyChartPriceResponse.class)
                .block();
    }
}
