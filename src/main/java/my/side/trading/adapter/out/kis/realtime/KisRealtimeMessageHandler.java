package my.side.trading.adapter.out.kis.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.dto.OverseasRealtimeQuote;
import my.side.trading.adapter.out.kis.util.KisAesUtil;
import my.side.trading.adapter.out.realtime.InMemoryRealtimePriceProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 현재 구현은 수도 코드 성격의 흐름을 일부 포함한다.
 * 지금은 실시간 호가 수신에만 집중하고 있다.
 * 정리 예정: 실시간 소켓을 연결할 서비스 구조를 확정한 뒤 이 클래스도 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisRealtimeMessageHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryRealtimePriceProvider priceProvider;

    // 체결통보용 AES key/iv (실시간 체결통보 구독 성공 시 설정)
    private volatile String aesKey;
    private volatile String aesIv;

    public void handleMessage(String data) {
        // 1. JSON인지 실데이터 문자열인지 구분
        if (data.startsWith("{")) {
            handleControlJson(data);
        } else {
            handleRealtimeString(data);
        }
    }

    /**
     * SUBSCRIBE SUCCESS, ERROR, PINGPONG 등 제어 JSON 응답을 처리한다.
     */
    private void handleControlJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode header = root.path("header");
            String trId = header.path("tr_id").asText();

            if ("PINGPONG".equals(trId)) {
                log.debug("PINGPONG을 수신했습니다.");
                // 필요하면 여기서 pong을 전송한다.
                return;
            }

            JsonNode body = root.path("body");
            String rtCd = body.path("rt_cd").asText();
            String msg = body.path("msg1").asText();

            if (!"0".equals(rtCd)) {
                log.warn("KIS WS 오류 응답입니다: rt_cd={}, msg={}", rtCd, msg);
                return;
            }

            log.info("KIS WS 구독이 성공했습니다: tr_id={}, msg={}", trId, msg);

            // 체결통보 TR_ID에 대해서는 AES key/iv를 저장한다.
            if (isSigningNoticeTrId(trId)) {
                JsonNode output = body.path("output");
                this.aesKey = output.path("key").asText();
                this.aesIv = output.path("iv").asText();
                // 보안상 AES key/iv는 마스킹해서 로그에 남긴다.
                log.info("KIS WS AES KEY/IV 저장 완료 (key=***masked***, iv=***masked***)");
            }

        } catch (Exception e) {
            throw new IllegalStateException("KIS WS JSON 응답 파싱 실패: " + json, e);
        }
    }

    private boolean isSigningNoticeTrId(String trId) {
        // 국내/해외 체결통보 TR_ID를 여기서 관리한다.
        // 예시: H0STCNI0/H0STCNI9 (국내), 해외 체결통보 TR_ID도 필요시 추가
        return "H0STCNI0".equals(trId)
                || "H0STCNI9".equals(trId);
    }

    /**
     * "0|TR_ID|...|payload" 형태의 실데이터를 처리한다.
     */
    private void handleRealtimeString(String data) {
        // 예: 0|HDFSASP0|001|RNASQQQM^... (해외주식 실시간호가)
        // 예: 1|H0STCNI0|001|<암호화된Base64문자열> (체결통보)
        char flag = data.charAt(0); // '0' or '1'
        String[] parts = data.split("\\|", 4); // 앞 4개만 분리

        if (parts.length < 4) {
            log.warn("KIS WS 실시간 데이터 형식이 올바르지 않습니다: {}", data);
            return;
        }

        String trId = parts[1];
        String countOrEtc = parts[2]; // 체결건수 등
        String payload = parts[3]; // 실제 데이터 (호가/체결/암호화데이터)

        // 0/1 flag보다 TR_ID 기준으로 분기하는 편이 더 직관적이다.
        switch (trId) {
            case "HDFSASP0": // 해외주식 실시간호가
                handleOverseasQuote(payload);
                break;

            case "H0STCNT0": // (예시) 국내 실시간 체결가
                handleDomesticTick(countOrEtc, payload);
                break;

            case "H0STCNI0":
            case "H0STCNI9":
                handleSigningNoticeEncrypted(payload);
                break;

            default:
                log.warn("KIS WS에서 알 수 없는 tr_id를 받았습니다: {}", trId);
        }
    }

    /**
     * 해외주식 실시간 호가(평문)를 '^' 기준으로 파싱한다.
     */
    private void handleOverseasQuote(String payload) {
        // 공백 필드까지 포함해 분리한다.
        String[] f = payload.split("\\^", -1);

        // RSYM ~ DASK1 까지 최소 17개 필드 필요
        if (f.length < 17) {
            log.error("해외호가 필드 개수 부족: 필드수={}", f.length);
            return;
        }

        // 0 ~ 10 : 헤더/총량
        String realtimeSymbol = f[0]; // RSYM
        String symbol = f[1]; // SYMB
        int decimalPlaces = parseIntSafe(f[2]); // ZDIV
        String localDate = f[3]; // XYMD
        String localTime = f[4]; // XHMS
        String krDate = f[5]; // KYMD
        String krTime = f[6]; // KHMS
        long totalBidVolume = parseLongSafe(f[7]); // BVOL
        long totalAskVolume = parseLongSafe(f[8]); // AVOL
        long totalBidVolumeChange = parseLongSafe(f[9]); // BDVL
        long totalAskVolumeChange = parseLongSafe(f[10]); // ADVL

        // 11 ~ 16 : 1호가
        double bidPrice1 = parseDoubleSafe(f[11]); // PBID1
        double askPrice1 = parseDoubleSafe(f[12]); // PASK1
        long bidVolume1 = parseLongSafe(f[13]); // VBID1
        long askVolume1 = parseLongSafe(f[14]); // VASK1
        long bidVolumeChange1 = parseLongSafe(f[15]); // DBID1
        long askVolumeChange1 = parseLongSafe(f[16]); // DASK1

        OverseasRealtimeQuote quote = new OverseasRealtimeQuote(
                realtimeSymbol,
                symbol,
                decimalPlaces,
                localDate,
                localTime,
                krDate,
                krTime,
                totalBidVolume,
                totalAskVolume,
                totalBidVolumeChange,
                totalAskVolumeChange,
                bidPrice1,
                askPrice1,
                bidVolume1,
                askVolume1,
                bidVolumeChange1,
                askVolumeChange1);

        // 여기서 서비스, 캐시, 이벤트 발행 등 후속 처리로 넘긴다.
        BigDecimal bestBid = BigDecimal.valueOf(quote.bidPrice1());
        BigDecimal bestAsk = BigDecimal.valueOf(quote.askPrice1());
        BigDecimal lastPrice = bestBid.add(bestAsk)
                .divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
        priceProvider.updateQuote(quote.symbol(), lastPrice, bestBid, bestAsk);
    }

    /**
     * 국내 체결가 예시를 평문 '^' 기준으로 분리해 처리한다.
     */
    private void handleDomesticTick(String countStr, String payload) {
        int tickCount = Integer.parseInt(countStr);
        String[] fields = payload.split("\\^");
        // tickCount와 fields 길이를 기준으로 순회 처리한다. (Python 예제와 같은 패턴)
        // ...
    }

    /**
     * 암호화된 체결통보 payload를 복호화한 뒤 '^' 기준으로 파싱한다.
     */
    private void handleSigningNoticeEncrypted(String encryptedPayload) {
        if (aesKey == null || aesIv == null) {
            log.warn("AES KEY/IV 미설정 상태에서 체결통보 수신 – 무시");
            return;
        }

        String decrypted = KisAesUtil.decryptAesCbcBase64(aesKey, aesIv, encryptedPayload);
        String[] fields = decrypted.split("\\^");

        // KIS 문서의 "실시간 체결통보" layout 순서대로 필드를 매핑한다.
        // 예시: 고객ID | 계좌번호 | 주문번호 | ...
        String customerId = fields[0];
        String accountNo = fields[1];
        String orderNo = fields[2];
        String origOrderNo = fields[3];
        String buySellType = fields[4];
        // ... 이후 필요한 만큼 필드 사용

        // 보안상 계좌번호는 마스킹해 로그에 남긴다. (주문번호는 추적용으로 유지)
        log.info("체결통보 수신: 계좌={}, 주문번호={}, 매도/매수={}",
                maskAccountNo(accountNo), orderNo, buySellType);

        // 여기서 이벤트 발행이나 서비스 호출로 알림/DB 기록 등을 처리한다.
    }

    /**
     * 계좌번호 마스킹: 뒤 4자리만 노출
     * 예: "1234567890" → "******7890"
     */
    private String maskAccountNo(String accountNo) {
        if (accountNo == null || accountNo.length() <= 4) {
            return "****";
        }
        return "*".repeat(accountNo.length() - 4) + accountNo.substring(accountNo.length() - 4);
    }

    private int parseIntSafe(String v) {
        if (v == null || v.isBlank())
            return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLongSafe(String v) {
        if (v == null || v.isBlank())
            return 0L;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double parseDoubleSafe(String v) {
        if (v == null || v.isBlank())
            return 0d;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

}
