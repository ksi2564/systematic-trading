package my.side.trading.adapter.out.yahoo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Yahoo Finance API v8 응답 DTO
 * API: https://query1.finance.yahoo.com/v8/finance/chart/{symbol}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YahooQuoteResponse(
        @JsonProperty("chart") Chart chart) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chart(
            @JsonProperty("result") List<Result> result,
            @JsonProperty("error") Object error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("meta") Meta meta,
            @JsonProperty("indicators") Indicators indicators) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            @JsonProperty("regularMarketPrice") BigDecimal regularMarketPrice,
            @JsonProperty("previousClose") BigDecimal previousClose,
            @JsonProperty("symbol") String symbol) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Indicators(
            @JsonProperty("quote") List<Quote> quote,
            @JsonProperty("adjclose") List<AdjClose> adjclose) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(
            @JsonProperty("close") List<BigDecimal> close,
            @JsonProperty("open") List<BigDecimal> open,
            @JsonProperty("high") List<BigDecimal> high,
            @JsonProperty("low") List<BigDecimal> low,
            @JsonProperty("volume") List<Long> volume) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdjClose(
            @JsonProperty("adjclose") List<BigDecimal> adjclose) {
    }

    /**
     * 현재가(regularMarketPrice) 반환
     */
    public BigDecimal getCurrentPrice() {
        if (chart == null || chart.result() == null || chart.result().isEmpty()) {
            return null;
        }
        return chart.result().get(0).meta().regularMarketPrice();
    }

    /**
     * 종가 리스트 반환 (과거 데이터 조회 시)
     */
    public List<BigDecimal> getClosePrices() {
        if (chart == null || chart.result() == null || chart.result().isEmpty()) {
            return List.of();
        }
        Result result = chart.result().get(0);
        if (result.indicators() == null || result.indicators().quote() == null
                || result.indicators().quote().isEmpty()) {
            return List.of();
        }
        List<BigDecimal> closes = result.indicators().quote().get(0).close();
        return closes != null ? closes.stream().filter(c -> c != null).toList() : List.of();
    }
}
