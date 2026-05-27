package my.side.trading.adapter.in.web.kis;

import my.side.trading.adapter.out.kis.dto.RealtimeQuoteSubscriptionResult;
import my.side.trading.adapter.out.kis.dto.SymbolTarget;

import java.util.List;

public record RealtimeQuoteSubscriptionResponse(
        String status,
        String symbol,
        String excd,
        boolean targetAdded,
        boolean connectionRestarted,
        List<Target> activeTargets
) {

    public static RealtimeQuoteSubscriptionResponse from(RealtimeQuoteSubscriptionResult result) {
        return new RealtimeQuoteSubscriptionResponse(
                result.status(),
                result.symbol(),
                result.excd(),
                result.targetAdded(),
                result.connectionRestarted(),
                result.activeTargets().stream()
                        .map(Target::from)
                        .toList()
        );
    }

    public record Target(String symbol, String excd) {
        public static Target from(SymbolTarget target) {
            return new Target(target.symbol(), target.excd());
        }
    }
}
