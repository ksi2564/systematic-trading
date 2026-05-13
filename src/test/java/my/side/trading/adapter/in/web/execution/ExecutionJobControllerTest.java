package my.side.trading.adapter.in.web.execution;

import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.core.adapter.in.web.common.GlobalExceptionHandler;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.execution.ExecutionOrderConfirmationResult;
import my.side.trading.core.application.execution.ExecutionOrderConfirmationService;
import my.side.trading.core.application.orchestration.ManualRebalancePreview;
import my.side.trading.core.application.orchestration.ManualRebalancePreviewService;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import my.side.trading.core.domain.execution.plan.RebalanceDecision;
import my.side.trading.core.domain.execution.plan.RebalanceType;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.strategy.DdBucket;
import my.side.trading.core.domain.strategy.StrategyPhase;
import my.side.trading.core.domain.strategy.StrategyState;
import my.side.trading.core.domain.strategy.WeightSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionJobControllerTest {

    private final ExecutionJobExecutor executor = mock(ExecutionJobExecutor.class);
    private final ExecutionOrderConfirmationService confirmationService = mock(ExecutionOrderConfirmationService.class);
    private final RebalanceOrchestrator rebalanceOrchestrator = mock(RebalanceOrchestrator.class);
    private final ManualRebalancePreviewService previewService = mock(ManualRebalancePreviewService.class);
    private final StrategyEodScheduler eodScheduler = mock(StrategyEodScheduler.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:34:56Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExecutionJobController(
                        executor,
                        confirmationService,
                        rebalanceOrchestrator,
                        previewService,
                        eodScheduler,
                        clock))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 확인필요_주문_확인_응답을_반환한다() throws Exception {
        when(confirmationService.confirm(eq(7L), eq(3L), any(Instant.class)))
                .thenReturn(new ExecutionOrderConfirmationResult(
                        7L,
                        3L,
                        ExecutionOrderStatus.CONFIRMATION_REQUIRED,
                        ExecutionOrderStatus.ACCEPTED,
                        "OD123",
                        OrderInquiryResult.Status.FOUND,
                        "주문 확인 완료"));

        mockMvc.perform(post("/api/jobs/7/orders/3/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(7L))
                .andExpect(jsonPath("$.data.orderId").value(3L))
                .andExpect(jsonPath("$.data.previousStatus").value("CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.data.currentStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.brokerOrderId").value("OD123"))
                .andExpect(jsonPath("$.data.inquiryStatus").value("FOUND"));

        verify(confirmationService).confirm(eq(7L), eq(3L), eq(Instant.parse("2026-05-07T12:34:56Z")));
    }

    @Test
    void 수동_리밸런싱_미리보기_응답을_반환한다() throws Exception {
        when(previewService.preview()).thenReturn(preview());

        mockMvc.perform(get("/api/jobs/manual-rebalance/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generatedAt").exists())
                .andExpect(jsonPath("$.data.operatingMode").value("MANUAL_LIVE"))
                .andExpect(jsonPath("$.data.signalDate").exists())
                .andExpect(jsonPath("$.data.decision.shouldRebalance").value(true))
                .andExpect(jsonPath("$.data.marketIndicators.vix").value(18.5))
                .andExpect(jsonPath("$.data.duplicateSignalJobExists").value(false))
                .andExpect(jsonPath("$.data.totalOrderNotional").value(0))
                .andExpect(jsonPath("$.data.executable").value(false));

        verify(previewService).preview();
    }

    private ManualRebalancePreview preview() {
        WeightSet targetWeights = WeightSet.of(60, 30, 10);
        StrategyState state = new StrategyState(
                LocalDate.of(2026, 5, 6),
                new BigDecimal("500.00"),
                new BigDecimal("450.00"),
                new BigDecimal("10.00"),
                new BigDecimal("20.00"),
                DdBucket.LESS_THAN_15,
                StrategyPhase.DRAWDOWN,
                targetWeights,
                true,
                1);
        RebalanceDecision decision = RebalanceDecision.yes(
                RebalanceType.THRESHOLD,
                "test",
                targetWeights,
                List.of());
        return new ManualRebalancePreview(
                Instant.parse("2026-05-07T12:34:56Z"),
                OperatingMode.MANUAL_LIVE,
                null,
                state.asOfDate(),
                state,
                decision,
                new Portfolio(new BigDecimal("1000.00"), List.of()),
                new BigDecimal("18.50"),
                new BigDecimal("440.00"),
                false,
                List.of(),
                BigDecimal.ZERO,
                new BigDecimal("1000.00"),
                false);
    }
}
