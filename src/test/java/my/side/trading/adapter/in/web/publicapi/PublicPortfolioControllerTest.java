package my.side.trading.adapter.in.web.publicapi;

import my.side.trading.core.application.portfolio.PublicPortfolioPerformanceView;
import my.side.trading.core.application.portfolio.PublicPortfolioReadService;
import my.side.trading.core.application.portfolio.PublicPortfolioSummaryView;
import my.side.trading.core.infrastructure.config.PublicPathProtectionMode;
import my.side.trading.core.infrastructure.config.TradingSecurityProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicPortfolioControllerTest {

    private final PublicPortfolioReadService publicPortfolioReadService = mock(PublicPortfolioReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TradingSecurityProps securityProps = new TradingSecurityProps(
                "test-key",
                List.of("/public/api"),
                PublicPathProtectionMode.REVERSE_PROXY,
                "공개 API는 리버스 프록시 뒤에서만 노출한다.",
                List.of("https://portfolio.example.com"),
                120,
                300
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicPortfolioController(publicPortfolioReadService, securityProps))
                .build();
    }

    @Test
    void summary는_캐시헤더와_비율중심_응답을_반환한다() throws Exception {
        when(publicPortfolioReadService.getSummary()).thenReturn(new PublicPortfolioSummaryView(
                true,
                LocalDate.of(2026, 4, 8),
                new BigDecimal("5.0000"),
                new BigDecimal("12.0000"),
                new BigDecimal("8.5000"),
                new PublicPortfolioSummaryView.HoldingWeights(
                        new BigDecimal("70.0000"),
                        new BigDecimal("20.0000"),
                        new BigDecimal("10.0000"),
                        new BigDecimal("0.0000")
                ),
                List.of(new PublicPortfolioSummaryView.MonthlyReturn("2026-04", new BigDecimal("8.5000")))
        ));

        String body = mockMvc.perform(get("/public/api/v1/summary"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("cumulativeReturnPct");
        assertThat(body).contains("holdingWeights");
        assertThat(body).doesNotContain("latestNav");
        assertThat(body).doesNotContain("recentJobs");
    }

    @Test
    void performance는_정규화인덱스만_반환한다() throws Exception {
        when(publicPortfolioReadService.getPerformance(180)).thenReturn(new PublicPortfolioPerformanceView(
                new BigDecimal("100.0000"),
                List.of(new PublicPortfolioPerformanceView.DailyIndexPoint(
                        LocalDate.of(2026, 4, 8),
                        new BigDecimal("100.0000"))),
                List.of(new PublicPortfolioPerformanceView.MonthlyReturn(
                        "2026-04",
                        new BigDecimal("8.5000")))
        ));

        String body = mockMvc.perform(get("/public/api/v1/performance"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("baseIndex");
        assertThat(body).contains("recentDailyIndexSeries");
        assertThat(body).contains("monthlyReturnSeries");
        assertThat(body).doesNotContain("cumulativePnlAmount");
        assertThat(body).doesNotContain("cash");
    }
}
