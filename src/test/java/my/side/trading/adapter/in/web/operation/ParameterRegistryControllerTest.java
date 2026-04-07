package my.side.trading.adapter.in.web.operation;

import my.side.trading.adapter.in.web.operation.dto.RecordParameterChangeRequest;
import my.side.trading.core.adapter.in.web.common.GlobalExceptionHandler;
import my.side.trading.core.application.operation.ParameterRegistryConflictException;
import my.side.trading.core.application.operation.ParameterRegistryService;
import my.side.trading.core.domain.parameter.ParameterChangeEvent;
import my.side.trading.core.domain.parameter.ParameterRegistryKey;
import my.side.trading.core.domain.parameter.ParameterRegistryRecord;
import my.side.trading.core.domain.parameter.ParameterRegistryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.time.LocalDate;
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

class ParameterRegistryControllerTest {

    private final ParameterRegistryService parameterRegistryService = mock(ParameterRegistryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ParameterRegistryController(parameterRegistryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void 목록조회가정상응답을반환한다() throws Exception {
        when(parameterRegistryService.getRegistry()).thenReturn(List.of(record("35")));

        mockMvc.perform(get("/api/operations/parameter-registry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").value("VIX_THRESHOLD"))
                .andExpect(jsonPath("$.data[0].effectiveValue").value("35"));
    }

    @Test
    void history는기본limit20을사용한다() throws Exception {
        when(parameterRegistryService.getHistory(eq(ParameterRegistryKey.VIX_THRESHOLD), eq(20)))
                .thenReturn(List.of(event("35", "40")));

        mockMvc.perform(get("/api/operations/parameter-registry/history")
                        .param("key", "VIX_THRESHOLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].newValue").value("40"));

        verify(parameterRegistryService).getHistory(ParameterRegistryKey.VIX_THRESHOLD, 20);
    }

    @Test
    void post요청은필수값누락시badRequest를반환한다() throws Exception {
        mockMvc.perform(post("/api/operations/parameter-registry/history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "VIX_THRESHOLD",
                                  "observedEffectiveValue": "40",
                                  "requestedBy": "",
                                  "reason": "update",
                                  "status": "ADOPTED",
                                  "basis": "근거",
                                  "validationMethod": "검증",
                                  "validationSummary": "결과",
                                  "nextReviewDate": "2026-05-01",
                                  "relatedArtifacts": ["report"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void 잘못된key파라미터는badRequest를반환한다() throws Exception {
        mockMvc.perform(get("/api/operations/parameter-registry/history")
                        .param("key", "UNKNOWN_KEY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void 현재적용값충돌은conflict를반환한다() throws Exception {
        when(parameterRegistryService.recordChange(any()))
                .thenThrow(new ParameterRegistryConflictException("observed mismatch"));

        mockMvc.perform(post("/api/operations/parameter-registry/history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "VIX_THRESHOLD",
                                  "observedEffectiveValue": "40",
                                  "requestedBy": "alice",
                                  "reason": "update",
                                  "status": "ADOPTED",
                                  "basis": "근거",
                                  "validationMethod": "검증",
                                  "validationSummary": "결과",
                                  "nextReviewDate": "2026-05-01",
                                  "relatedArtifacts": ["report"]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"));
    }

    private ParameterRegistryRecord record(String effectiveValue) {
        return new ParameterRegistryRecord(
                ParameterRegistryKey.VIX_THRESHOLD,
                effectiveValue,
                ParameterRegistryStatus.PROVISIONAL,
                "근거",
                "검증",
                "결과",
                LocalDate.of(2026, 5, 1),
                List.of("report"),
                "system",
                Instant.parse("2026-04-07T00:00:00Z"));
    }

    private ParameterChangeEvent event(String previousValue, String newValue) {
        return new ParameterChangeEvent(
                1L,
                ParameterRegistryKey.VIX_THRESHOLD,
                previousValue,
                newValue,
                "alice",
                "update",
                ParameterRegistryStatus.ADOPTED,
                "근거",
                "검증",
                "결과",
                LocalDate.of(2026, 5, 1),
                List.of("report"),
                Instant.parse("2026-04-07T00:00:00Z"));
    }
}
