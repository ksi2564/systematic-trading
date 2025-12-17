package my.side.trading.adapter.out.kis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.dto.SymbolTarget;
import my.side.trading.adapter.out.kis.realtime.KisRealtimeMessageHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisOverseasRealtimeQuoteService {

    private final ReactorNettyWebSocketClient wsClient;
    private final KisAuthService kisAuthService;
    private final KisRealtimeMessageHandler messageHandler;

    // KIS 해외주식 실시간호가 WebSocket URL
    private static final String WS_URL =
            "ws://ops.koreainvestment.com:21000/tryitout/HDFSASP0";

    /**
     * QQQ / QLD / TQQQ 3종목 실시간호가를
     * WebSocket 하나로 동시에 구독
     * 애플리케이션 기동 시 자동 실행 예정
     */
    public void startCoreEtfRealtimeQuotes() {
        List<SymbolTarget> targets = List.of(
                new SymbolTarget("QQQ", "NAS"),
                new SymbolTarget("QLD", "AMS"),
                new SymbolTarget("TQQQ", "NAS")
        );

        connectAndSubscribe(targets)
                .doOnSubscribe(s -> log.info("[KIS WS] 해외 실시간호가 연결 시도 시작"))
                .retryWhen(
                        Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))   // 5초부터 백오프
                                .maxBackoff(Duration.ofMinutes(1))             // 최대 1분 간격
                                .jitter(0.5)                          // 약간 랜덤
                                .doBeforeRetry(rs ->
                                        log.warn("[KIS WS] 연결 종료 감지, 재연결 시도 {}회차. reason={}",
                                                rs.totalRetries() + 1,
                                                rs.failure().toString())
                                )
                )
                .subscribe(
                        null,
                        e -> log.error("[KIS WS] 재연결 전략으로도 회복 불가, 스트림 종료", e)
                );
    }

    /**
     * 실제 WebSocket 1회 연결 + 구독 로직
     * 이 Mono가 에러로 종료되면 위의 retryWhen이 다시 실행한다.
     */
    private Mono<Void> connectAndSubscribe(List<SymbolTarget> targets) {
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
                                    log.error("[KIS WS] 수신 처리 중 오류 발생: {}", e.getMessage(), e)
                            )
                            .doFinally(sig ->
                                    log.warn("[KIS WS] 수신 스트림 종료. signal={}", sig)
                            )
                            .then();

                    // send 완료 후 receive 구독
                    return send.then(receive);
                }
        ).doOnError(e ->
                log.error("[KIS WS] 최상위 WebSocket execute 에러 발생: {}", e.getMessage(), e)
        );
    }

    /**
     * 컨트롤러 등에서 단일 종목 구독할 때 사용 (테스트용)
     */
    public void subscribeRealtimeQuote(String symbol, String excd) {
        connectAndSubscribe(List.of(new SymbolTarget(symbol, excd)));
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
        ZonedDateTime nowKst = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
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
}

