package my.side.trading.core.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 외부 시장 데이터 제공자 포트 인터페이스
 * - VIX 지수, QQQ 200일 이동평균 등 시장 보조지표 조회용
 * - core 계층에서 adapter(Yahoo Finance 등)에 직접 의존하지 않기 위한 추상화
 */
public interface MarketDataProvider {

    /**
     * VIX 현재가 조회
     *
     * @return VIX 현재가, 조회 실패 또는 비활성화 시 Optional.empty()
     */
    Optional<BigDecimal> getVixPrice();

    /**
     * QQQ 200일 이동평균 계산
     *
     * @return 200MA, 데이터 부족 또는 계산 불가 시 Optional.empty()
     */
    Optional<BigDecimal> getQqq200Ma();
}
