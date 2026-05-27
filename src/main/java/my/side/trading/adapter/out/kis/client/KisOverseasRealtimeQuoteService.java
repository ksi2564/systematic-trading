package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.dto.RealtimeQuoteSubscriptionResult;
import my.side.trading.adapter.out.kis.dto.SymbolTarget;
import my.side.trading.adapter.out.kis.realtime.KisRealtimeMessageHandler;
import my.side.trading.shared.security.SensitiveDataSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOverseasRealtimeQuoteService {

    private final ReactorNettyWebSocketClient wsClient;
    private final KisAuthService kisAuthService;
    private final KisRealtimeMessageHandler messageHandler;
    private final Clock clock;

    // KIS 해외주식 실시간호가 WebSocket URL
    private static final String WS_URL =
            "ws://ops.koreainvestment.com:21000/tryitout/HDFSASP0";
    private static final List<SymbolTarget> CORE_ETF_TARGETS = List.of(
            new SymbolTarget("QQQM", "NAS"),
            new SymbolTarget("QLD", "AMS"),
            new SymbolTarget("TQQQ", "NAS")
    );

    private final Object subscriptionMonitor = new Object();
    private final Set<SymbolTarget> desiredTargets = new LinkedHashSet<>();
    private Disposable managedSubscription;

    /**
     * QQQM / QLD / TQQQ 3종목 실시간호가를
     * WebSocket 하나로 동시에 구독
     * 애플리케이션 기동 시 자동 실행 예정
     */
    public void startCoreEtfRealtimeQuotes() {
        SubscriptionUpdate update = updateSubscriptions(CORE_ETF_TARGETS);
        log.info("[KIS WS] 핵심 ETF 실시간호가 구독 요청 처리. targetAdded={}, connectionRestarted={}, activeTargets={}",
                update.targetAdded(), update.connectionRestarted(), update.activeTargets());
    }

    /**
     * 실제 WebSocket 1회 연결 + 구독 로직
     * 이 Mono가 에러로 종료되면 위의 retryWhen이 다시 실행한다.
     */
    private Mono<Void> connectAndSubscribe(List<SymbolTarget> targets) {
        return Mono.defer(() -> {
            String approvalKey = kisAuthService.issueApprovalKey();

            return wsClient.execute(
                    URI.create(WS_URL),
                    session -> {
                        // tr_key 리스트 생성
                        List<String> trKeys = targets.stream()
                                .map(t -> buildUsTrKey(t.symbol(), t.excd()))
                                .toList();

                        // 구독 메시지 생성
                        Flux<WebSocketMessage> subscribeMessages = Flux.fromIterable(trKeys)
                                .map(trKey -> buildSubscribePayload(approvalKey, trKey))
                                .map(session::textMessage);

                        Mono<Void> send = session.send(subscribeMessages)
                                .doOnSubscribe(s -> log.info("[KIS WS] 구독 메시지 전송. trKeys={}", trKeys));

                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(messageHandler::handleMessage)
                                .doOnError(e ->
                                        log.error("[KIS WS] 수신 처리 중 오류 발생: {}",
                                                SensitiveDataSanitizer.sanitizeThrowable(e))
                                )
                                .doFinally(sig ->
                                        log.warn("[KIS WS] 수신 스트림 종료. signal={}", sig)
                                )
                                .then();

                        // send 완료 후 receive 구독
                        return send.then(receive);
                    }
            );
        }).doOnError(e ->
                log.error("[KIS WS] 최상위 WebSocket execute 에러 발생: {}",
                        SensitiveDataSanitizer.sanitizeThrowable(e))
        );
    }

    /**
     * 컨트롤러 등에서 단일 종목 구독할 때 사용 (테스트용)
     */
    public RealtimeQuoteSubscriptionResult subscribeRealtimeQuote(String symbol, String excd) {
        SymbolTarget target = normalizeTarget(symbol, excd);
        SubscriptionUpdate update = updateSubscriptions(List.of(target));
        log.info("[KIS WS] 단일 해외 실시간호가 구독 요청 처리. symbol={}, excd={}, targetAdded={}, connectionRestarted={}",
                target.symbol(), target.excd(), update.targetAdded(), update.connectionRestarted());
        return RealtimeQuoteSubscriptionResult.from(
                target,
                update.targetAdded(),
                update.connectionRestarted(),
                update.activeTargets()
        );
    }

    private SubscriptionUpdate updateSubscriptions(List<SymbolTarget> targets) {
        synchronized (subscriptionMonitor) {
            boolean targetAdded = false;
            for (SymbolTarget target : targets) {
                targetAdded = desiredTargets.add(target) || targetAdded;
            }

            boolean hasActiveSubscription = managedSubscription != null && !managedSubscription.isDisposed();
            boolean connectionRestarted = hasActiveSubscription && targetAdded;
            if (connectionRestarted) {
                managedSubscription.dispose();
                managedSubscription = null;
            }

            if (managedSubscription == null || managedSubscription.isDisposed()) {
                managedSubscription = subscribeManagedStream();
            }

            return new SubscriptionUpdate(
                    targetAdded,
                    connectionRestarted,
                    snapshotTargetsLocked()
            );
        }
    }

    private Disposable subscribeManagedStream() {
        return Mono.defer(() -> {
                    List<SymbolTarget> targets = snapshotTargets();
                    if (targets.isEmpty()) {
                        log.warn("[KIS WS] 구독 대상이 없어 해외 실시간호가 연결을 시작하지 않습니다.");
                        return Mono.empty();
                    }
                    return connectAndSubscribe(targets);
                })
                .doOnSubscribe(s -> log.info("[KIS WS] 해외 실시간호가 관리형 연결 시도 시작. activeTargets={}",
                        snapshotTargets()))
                .retryWhen(
                        Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))   // 5초부터 백오프
                                .maxBackoff(Duration.ofMinutes(1))             // 최대 1분 간격
                                .jitter(0.5)                                   // 약간 랜덤
                                .doBeforeRetry(rs ->
                                        log.warn("[KIS WS] 연결 종료 감지, 재연결 시도 {}회차. reason={}",
                                                rs.totalRetries() + 1,
                                                SensitiveDataSanitizer.sanitizeThrowable(rs.failure()))
                                )
                )
                .subscribe(
                        null,
                        e -> log.error("[KIS WS] 재연결 전략으로도 회복 불가, 스트림 종료. reason={}",
                                SensitiveDataSanitizer.sanitizeThrowable(e))
                );
    }

    private List<SymbolTarget> snapshotTargets() {
        synchronized (subscriptionMonitor) {
            return snapshotTargetsLocked();
        }
    }

    private List<SymbolTarget> snapshotTargetsLocked() {
        return List.copyOf(desiredTargets);
    }

    private SymbolTarget normalizeTarget(String symbol, String excd) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol은 비워둘 수 없습니다.");
        }
        if (excd == null || excd.isBlank()) {
            throw new IllegalArgumentException("excd는 비워둘 수 없습니다.");
        }

        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        String normalizedExcd = excd.trim().toUpperCase(Locale.ROOT);
        return new SymbolTarget(normalizedSymbol, validateExcd(normalizedExcd));
    }

    private String validateExcd(String excd) {
        return switch (excd) {
            case "NAS", "NYS", "AMS" -> excd;
            default -> throw new IllegalArgumentException("지원하지 않는 EXCD: " + excd);
        };
    }

    private String buildSubscribePayload(String approvalKey, String trKey) {
        return """
                {
                  "header": {
                    "approval_key": "%s",
                    "custtype": "P",
                    "tr_type": "1",
                    "content-type": "utf-8"
                  },
                  "body": {
                    "input": {
                      "tr_id": "HDFSASP0",
                      "tr_key": "%s"
                    }
                  }
                }
                """.formatted(approvalKey, trKey);
    }

    private boolean isKisDaySession() {
        ZonedDateTime nowKst = ZonedDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        LocalTime time = nowKst.toLocalTime();

        return !time.isBefore(LocalTime.of(6, 0))
                && !time.isAfter(LocalTime.of(23, 30));
    }

    /**
     * EXCD(시장코드) + 종목 + 시간대 기준으로 tr_key 생성
     * - KIS 기준 야간 세션(미장 정규장): D + EXCD + 종목
     * - KIS 기준 주간 세션: R + (BAQ/BAY/BAA) + 종목
     */
    private String buildUsTrKey(String symbol, String excd) {
        if (isKisDaySession()) {
            String dayMarketCode = switch (excd) {
                case "NAS" -> "BAQ";
                case "NYS" -> "BAY";
                case "AMS" -> "BAA";
                default -> throw new IllegalArgumentException("지원하지 않는 EXCD: " + excd);
            };
            return "R" + dayMarketCode + symbol;
        } else {
            return "D" + excd + symbol;
        }
    }

    private record SubscriptionUpdate(
            boolean targetAdded,
            boolean connectionRestarted,
            List<SymbolTarget> activeTargets
    ) {
    }
}

