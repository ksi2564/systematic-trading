package my.side.trading.adapter.in.web.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.portfolio.PerformanceAnalyticsBackfillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PerformanceAnalyticsControllerTest {

    private final PerformanceAnalyticsBackfillService backfillService = mock(PerformanceAnalyticsBackfillService.class);
    private final MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PerformanceAnalyticsController(
                backfillService,
                marketCalendarService)).build();
    }

    @Test
    void 요청한_기간으로_재집계한다() throws Exception {
        when(backfillService.rebuild(eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 3))))
                .thenReturn(new PerformanceAnalyticsBackfillService.RebuildResult(
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 4, 3),
                        3,
                        2
                ));

        mockMvc.perform(post("/api/operations/performance-analytics/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startDate", "2026-04-01",
                                "endDate", "2026-04-03"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate[0]").value(2026))
                .andExpect(jsonPath("$.data.startDate[1]").value(4))
                .andExpect(jsonPath("$.data.startDate[2]").value(1))
                .andExpect(jsonPath("$.data.endDate[0]").value(2026))
                .andExpect(jsonPath("$.data.endDate[1]").value(4))
                .andExpect(jsonPath("$.data.endDate[2]").value(3))
                .andExpect(jsonPath("$.data.processedCount").value(3))
                .andExpect(jsonPath("$.data.actualReadyCount").value(2));

        verify(backfillService).rebuild(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3));
    }

    @Test
    void endDate가_없으면_현재_시장일자로_기본범위를_재집계한다() throws Exception {
        when(marketCalendarService.currentMarketDate()).thenReturn(LocalDate.of(2026, 4, 3));
        when(backfillService.rebuildDefaultRange(eq(LocalDate.of(2026, 4, 3))))
                .thenReturn(new PerformanceAnalyticsBackfillService.RebuildResult(
                        LocalDate.of(2026, 3, 30),
                        LocalDate.of(2026, 4, 3),
                        5,
                        4
                ));

        mockMvc.perform(post("/api/operations/performance-analytics/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endDate[0]").value(2026))
                .andExpect(jsonPath("$.data.endDate[1]").value(4))
                .andExpect(jsonPath("$.data.endDate[2]").value(3))
                .andExpect(jsonPath("$.data.processedCount").value(5))
                .andExpect(jsonPath("$.data.actualReadyCount").value(4));

        verify(backfillService).rebuildDefaultRange(LocalDate.of(2026, 4, 3));
    }
}
