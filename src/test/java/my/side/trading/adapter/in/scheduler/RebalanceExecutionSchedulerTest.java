package my.side.trading.adapter.in.scheduler;

import my.side.trading.core.application.execution.ExecutionGuard;
import my.side.trading.core.application.market.MarketCalendarService;
import my.side.trading.core.application.orchestration.RebalanceOrchestrator;
import my.side.trading.core.domain.execution.ExecutionTriggerType;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.time.MarketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RebalanceExecutionSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-02T13:45:00Z"), ZoneOffset.UTC);

    @Test
    void 자동_리밸런싱은_미국_동부시간_기준_정규장_개장_15분_뒤에_실행된다() throws NoSuchMethodException {
        Method scheduledMethod = RebalanceExecutionScheduler.class.getMethod("runRebalance");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);

        org.assertj.core.api.Assertions.assertThat(scheduled).isNotNull();
        org.assertj.core.api.Assertions.assertThat(scheduled.cron()).isEqualTo("0 45 9 * * MON-FRI");
        org.assertj.core.api.Assertions.assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }

    @Test
    void 휴장일이면_자동_리밸런싱을_건너뛴다() {
        ExecutionGuard guard = mock(ExecutionGuard.class);
        RebalanceOrchestrator orchestrator = mock(RebalanceOrchestrator.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        LocalDate marketDate = LocalDate.of(2026, 7, 3);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(MarketStatus.HOLIDAY);
        when(guard.currentMode()).thenReturn(OperatingMode.AUTO_LIVE);

        RebalanceExecutionScheduler scheduler = new RebalanceExecutionScheduler(
                guard,
                orchestrator,
                marketCalendarService,
                FIXED_CLOCK);

        scheduler.runRebalance();

        verify(guard, never()).getExecutionBlockReason(any());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void 정규장이면_기존_실행가드를_통과한_후_오케스트레이터를_호출한다() {
        ExecutionGuard guard = mock(ExecutionGuard.class);
        RebalanceOrchestrator orchestrator = mock(RebalanceOrchestrator.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        LocalDate marketDate = LocalDate.of(2026, 4, 2);

        when(marketCalendarService.currentMarketDate()).thenReturn(marketDate);
        when(marketCalendarService.getMarketStatus(marketDate)).thenReturn(MarketStatus.REGULAR);
        when(guard.getExecutionBlockReason(ExecutionTriggerType.AUTOMATED)).thenReturn(Optional.empty());

        RebalanceExecutionScheduler scheduler = new RebalanceExecutionScheduler(
                guard,
                orchestrator,
                marketCalendarService,
                FIXED_CLOCK);

        scheduler.runRebalance();

        verify(guard).getExecutionBlockReason(ExecutionTriggerType.AUTOMATED);
        verify(orchestrator).run(eq(Instant.parse("2026-04-02T13:45:00Z")), eq(ExecutionTriggerType.AUTOMATED));
    }
}
