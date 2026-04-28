package my.side.trading.adapter.in.web.kis.diagnostics;

import my.side.trading.adapter.out.kis.dto.KisTokenDiagnosticsResult;
import my.side.trading.adapter.out.kis.dto.KisTokenDiagnosticsSnapshot;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.core.adapter.in.web.common.GlobalExceptionHandler;
import my.side.trading.core.domain.execution.order.ExecutionOrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KisDevDiagnosticsControllerTest {

    private final KisDevDiagnosticsService diagnosticsService = mock(KisDevDiagnosticsService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new KisDevDiagnosticsController(diagnosticsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void 주문_payload_dry_run_응답은_실행하지_않는다() throws Exception {
        OverseasOrderRequest body = new OverseasOrderRequest(
                "12345678",
                "01",
                "NASD",
                "QQQ",
                "3",
                "421.12",
                "0",
                "00"
        );
        when(diagnosticsService.buildOrderPayload(any()))
                .thenReturn(new KisDevOrderPayloadResponse(
                        "/uapi/overseas-stock/v1/trading/order",
                        "TTTT1002U",
                        ExecutionOrderSide.BUY,
                        body,
                        false
                ));

        mockMvc.perform(post("/kis/dev-diagnostics/orders/payload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "QQQ",
                                  "side": "BUY",
                                  "quantity": 3,
                                  "limitPrice": 421.12
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderPath").value("/uapi/overseas-stock/v1/trading/order"))
                .andExpect(jsonPath("$.data.tr_id").value("TTTT1002U"))
                .andExpect(jsonPath("$.data.willExecute").value(false))
                .andExpect(jsonPath("$.data.body.PDNO").value("QQQ"))
                .andExpect(jsonPath("$.data.body.ORD_QTY").value("3"));
    }

    @Test
    void 주문_payload_입력이_잘못되면_거부한다() throws Exception {
        mockMvc.perform(post("/kis/dev-diagnostics/orders/payload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "qqq",
                                  "side": "BUY",
                                  "quantity": 0,
                                  "limitPrice": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void 토큰_상태는_raw_token을_반환하지_않는다() throws Exception {
        when(diagnosticsService.inspectTokenCache())
                .thenReturn(new KisTokenDiagnosticsSnapshot(
                        true,
                        true,
                        Instant.parse("2026-04-28T12:00:00Z"),
                        300,
                        18,
                        "abcdef123456"
                ));

        mockMvc.perform(get("/kis/dev-diagnostics/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenLength").value(18))
                .andExpect(jsonPath("$.data.fingerprint").value("abcdef123456"))
                .andExpect(content().string(not(containsString("secret-token-value"))));
    }

    @Test
    void 토큰_refresh도_raw_token을_반환하지_않는다() throws Exception {
        when(diagnosticsService.refreshToken())
                .thenReturn(new KisTokenDiagnosticsResult(
                        true,
                        "REMOTE",
                        new KisTokenDiagnosticsSnapshot(
                                true,
                                true,
                                Instant.parse("2026-04-28T12:00:00Z"),
                                300,
                                18,
                                "abcdef123456"
                        )
                ));

        mockMvc.perform(post("/kis/dev-diagnostics/token/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.token.tokenLength").value(18))
                .andExpect(content().string(not(containsString("secret-token-value"))));
    }

    @Test
    void approval_key_refresh도_raw_key를_반환하지_않는다() throws Exception {
        when(diagnosticsService.refreshApprovalKey())
                .thenReturn(new KisApprovalKeyDiagnosticsResponse(
                        true,
                        20,
                        "123456abcdef",
                        false
                ));

        mockMvc.perform(post("/kis/dev-diagnostics/approval-key/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issued").value(true))
                .andExpect(jsonPath("$.data.approvalKeyLength").value(20))
                .andExpect(jsonPath("$.data.rawApprovalKeyReturned").value(false))
                .andExpect(content().string(not(containsString("secret-approval-key"))));
    }
}
