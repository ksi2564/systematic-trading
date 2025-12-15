package my.side.trading.core.domain.portfolio;

import java.math.BigDecimal;
import java.util.List;

public interface OverseasAccountReader {

    /**
     * KIS 잔고 조회 API를 감싼 인터페이스.
     * 구현체에서 KIS 응답(JSON)을 파싱해서 이 도메인으로 변환
     */
    AccountSnapshot getAccountSnapshot();

    record AccountSnapshot(
            BigDecimal cash,                  // 현금 (미국 달러)
            List<AccountPosition> positions   // 심볼별 보유 수량/평단
    ) {}

    record AccountPosition(
            String symbol,
            BigDecimal quantity,
            BigDecimal avgPrice
    ) {}
}
