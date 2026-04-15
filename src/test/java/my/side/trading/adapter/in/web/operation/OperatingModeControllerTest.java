package my.side.trading.adapter.in.web.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import my.side.trading.adapter.in.web.operation.dto.OperatingModeChangeRequest;
import my.side.trading.core.adapter.in.web.common.GlobalExceptionHandler;
import my.side.trading.core.application.operation.OperatingModeChangeResult;
import my.side.trading.core.application.operation.OperatingModeService;
import my.side.trading.core.application.operation.OperatingModeStatus;
import my.side.trading.core.domain.operation.OperatingMode;
import my.side.trading.core.domain.operation.OperatingModeAuditEvent;
import my.side.trading.core.domain.operation.OperatingModeTransitionType;
import my.side.trading.core.domain.operation.OperatingModeTriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperatingModeControllerTest {

    private final OperatingModeService operatingModeService = mock(OperatingModeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new OperatingModeController(operatingModeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void 현재_모드와_최근_이력을_반환한다() throws Exception {
        OperatingModeAuditEvent auditEvent = auditEvent(1L, OperatingMode.AUTO_LIVE);
        when(operatingModeService.getCurrentStatus(5)).thenReturn(new OperatingModeStatus(
                OperatingMode.AUTO_LIVE,
                true,
                List.of(auditEvent)));

        mockMvc.perform(get("/api/operations/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMode").value("AUTO_LIVE"))
                .andExpect(jsonPath("$.data.manualApprovalRecorded").value(true))
                .andExpect(jsonPath("$.data.recentHistory[0].id").value(1L));
    }

    @Test
    void requestedBy가_비어있으면_거부한다() throws Exception {
        OperatingModeChangeRequest request = new OperatingModeChangeRequest(
                OperatingMode.AUTO_LIVE,
                "",
                "promote");

        mockMvc.perform(post("/api/operations/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void 변경된_상태를_반환한다() throws Exception {
        OperatingModeAuditEvent auditEvent = auditEvent(2L, OperatingMode.AUTO_LIVE);
        when(operatingModeService.changeMode(eq(OperatingMode.AUTO_LIVE), eq("alice"), eq("promote")))
                .thenReturn(new OperatingModeChangeResult(OperatingMode.AUTO_LIVE, true, true, auditEvent));
        when(operatingModeService.recentHistory(5)).thenReturn(List.of(auditEvent));

        mockMvc.perform(post("/api/operations/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetMode": "AUTO_LIVE",
                                  "requestedBy": "alice",
                                  "reason": "promote"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentMode").value("AUTO_LIVE"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.latestAuditEvent.approvedBy").value("alice"));
    }

    @Test
    void 이력_조회는_기본_limit_20을_사용한다() throws Exception {
        when(operatingModeService.recentHistory(20)).thenReturn(List.of());

        mockMvc.perform(get("/api/operations/mode-history"))
                .andExpect(status().isOk());

        verify(operatingModeService).recentHistory(20);
    }

    private OperatingModeAuditEvent auditEvent(Long id, OperatingMode targetMode) {
        return new OperatingModeAuditEvent(
                id,
                OperatingMode.MANUAL_LIVE,
                targetMode,
                OperatingModeTransitionType.PROMOTION,
                OperatingModeTriggerSource.MANUAL_API,
                null,
                "alice",
                "promote",
                "alice",
                Instant.parse("2026-04-03T00:00:00Z"),
                Instant.parse("2026-04-03T00:00:00Z"));
    }
}
