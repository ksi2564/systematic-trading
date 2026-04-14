package my.side.trading.core.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

public interface CurrentFxRateProvider {

    Optional<BigDecimal> getCurrentUsdKrwRate();
}
