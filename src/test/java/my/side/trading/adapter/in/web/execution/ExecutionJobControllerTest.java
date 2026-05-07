package my.side.trading.adapter.in.web.execution;

import my.side.trading.adapter.in.scheduler.StrategyEodScheduler;
import my.side.trading.core.adapter.in.web.common.GlobalExceptionHandler;
import my.side.trading.core.application.execution.ExecutionJobExecutor;
import my.side.trading.core.application.execution.ExecutionOrderConfirmationResult;
import my.side.trading.core.application.execution.ExecutionOrderConfirmationService;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import my.side.trading.core.domain.execution.order.ExecutionOrderStatus;
import my.side.trading.core.domain.execution.order.OrderInquiryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionJobControllerTest {

    private final ExecutionJobExecutor executor = mock(ExecutionJobExecutor.class);
    private final ExecutionOrderConfirmationService confirmationService = mock(ExecutionOrderConfirmationService.class);
    private final RebalanceOrchestrator rebalanceOrchestrator = mock(RebalanceOrchestrator.class);
    private final StrategyEodScheduler eodScheduler = mock(StrategyEodScheduler.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-07T12:34:56Z"), ZoneOffset.UTC);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExecutionJobController(
                        executor,
                        confirmationService,
                        rebalanceOrchestrator,
                        eodScheduler,
                        clock))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 확인필요_주문_확인_응답을_반환한다() throws Exception {
        when(confirmationService.confirm(eq(7L), eq(3L), any(LocalDateTime.class)))
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

        verify(confirmationService).confirm(eq(7L), eq(3L), eq(LocalDateTime.of(2026, 5, 7, 12, 34, 56)));
    }
}
