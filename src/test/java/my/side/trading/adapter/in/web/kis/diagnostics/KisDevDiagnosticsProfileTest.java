package my.side.trading.adapter.in.web.kis.diagnostics;

import my.side.trading.adapter.out.kis.client.KisAuthService;
import my.side.trading.adapter.out.kis.client.KisOrderTrIdResolver;
import my.side.trading.adapter.out.kis.mapper.KisOverseasOrderRequestMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KisDevDiagnosticsProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DiagnosticsConfig.class)
            .withBean(KisOverseasOrderRequestMapper.class, () -> mock(KisOverseasOrderRequestMapper.class))
            .withBean(KisOrderTrIdResolver.class, () -> mock(KisOrderTrIdResolver.class))
            .withBean(KisAuthService.class, () -> mock(KisAuthService.class));

    @Test
    void local_profile에서는_진단_bean이_등록된다() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(KisDevDiagnosticsController.class);
                    assertThat(context).hasSingleBean(KisDevDiagnosticsService.class);
                });
    }

    @Test
    void dev_profile에서는_진단_bean이_등록된다() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasSingleBean(KisDevDiagnosticsController.class);
                    assertThat(context).hasSingleBean(KisDevDiagnosticsService.class);
                });
    }

    @Test
    void profile이_없으면_진단_bean이_등록되지_않는다() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(KisDevDiagnosticsController.class);
            assertThat(context).doesNotHaveBean(KisDevDiagnosticsService.class);
        });
    }

    @Test
    void prod_profile에서는_진단_bean이_등록되지_않는다() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KisDevDiagnosticsController.class);
                    assertThat(context).doesNotHaveBean(KisDevDiagnosticsService.class);
                });
    }

    @Test
    void prod와_local이_함께_켜져도_진단_bean이_등록되지_않는다() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod,local")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KisDevDiagnosticsController.class);
                    assertThat(context).doesNotHaveBean(KisDevDiagnosticsService.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            KisDevDiagnosticsController.class,
            KisDevDiagnosticsService.class
    })
    static class DiagnosticsConfig {
    }
}
