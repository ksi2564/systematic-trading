package my.side.trading.core.application.strategy;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.strategy.WeightSet;
import my.side.trading.core.infrastructure.jpa.entity.StrategyWeightRuleEntity;
import my.side.trading.core.infrastructure.jpa.repository.StrategyWeightRuleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@RequiredArgsConstructor
@Service
public class WeightRuleService {

    private final StrategyWeightRuleRepository repository;

    /**
     * DD 퍼센트와 전략 버전을 기준으로 목표 비중(WeightSet)을 조회한다.
     * 예: ddPercent = 15.23, version = 1
     */
    public WeightSet getTargetWeights(BigDecimal ddPercent, int version) {
        StrategyWeightRuleEntity rule = repository.findRule(ddPercent, version)
                .orElseThrow(() -> new IllegalStateException(
                        "해당 DD 구간에 대한 비중표가 없습니다. dd=" + ddPercent + ", version=" + version
                ));

        return new WeightSet(
                rule.getWQqq(),
                rule.getWQld(),
                rule.getWTqqq()
        );
    }
}
