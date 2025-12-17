package my.side.trading.core.application.portfolio;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.portfolio.OverseasAccountReader;
import my.side.trading.core.domain.portfolio.Portfolio;
import my.side.trading.core.domain.portfolio.Position;
import my.side.trading.core.domain.portfolio.RealtimePriceProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static my.side.trading.core.domain.portfolio.OverseasAccountReader.AccountSnapshot;

@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final OverseasAccountReader accountReader;
    private final RealtimePriceProvider priceProvider;

    /**
     * KIS 잔고 + 실시간 가격을 기반으로 현재 포트폴리오 상태를 계산한다.
     */
    public Portfolio getCurrentPortfolio() {
        AccountSnapshot snapshot = accountReader.getAccountSnapshot();

        List<Position> positions = snapshot.positions().stream()
                .map(pos -> {
                    BigDecimal lastPrice = priceProvider.getLastPrice(pos.symbol())
                            .orElseThrow(() -> new IllegalStateException(
                                    "실시간 가격을 찾을 수 없습니다. symbol=" + pos.symbol()
                            ));

                    return new Position(
                            pos.symbol(),
                            pos.quantity(),
                            pos.avgPrice(),
                            lastPrice
                    );
                })
                .collect(Collectors.toList());

        return new Portfolio(snapshot.cash(), positions);
    }
}
