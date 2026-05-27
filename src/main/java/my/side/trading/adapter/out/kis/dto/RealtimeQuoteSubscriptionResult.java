package my.side.trading.adapter.out.kis.dto;

import java.util.List;

public record RealtimeQuoteSubscriptionResult(
        String status,
        String symbol,
        String excd,
        boolean targetAdded,
        boolean connectionRestarted,
        List<SymbolTarget> activeTargets
) {

    public static RealtimeQuoteSubscriptionResult from(
            SymbolTarget target,
            boolean targetAdded,
            boolean connectionRestarted,
            List<SymbolTarget> activeTargets
    ) {
        return new RealtimeQuoteSubscriptionResult(
                resolveStatus(targetAdded, connectionRestarted),
                target.symbol(),
                target.excd(),
                targetAdded,
                connectionRestarted,
                List.copyOf(activeTargets)
        );
    }

    private static String resolveStatus(boolean targetAdded, boolean connectionRestarted) {
        if (connectionRestarted) {
            return "RESTARTED";
        }
        if (targetAdded) {
            return "SUBSCRIBED";
        }
        return "ALREADY_SUBSCRIBED";
    }
}
