package my.side.trading.adapter.out.kis.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.kis.dto.OverseasRealtimeQuote;
import my.side.trading.adapter.out.kis.util.KisAesUtil;
import my.side.trading.adapter.out.realtime.InMemoryRealtimePriceProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 해당 코드는 수도 코드를 포함하고 있음
 * 현재는 실시간 호가만 가져오는 중..
 * TODO: 실시간 소켓 붙여야하는 서비스 정리 후, 해당 class 코드도 정리할 것
 */
@Component
@RequiredArgsConstructor
public class KisRealtimeMessageHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryRealtimePriceProvider priceProvider;

    // 체결통보용 AES 키/IV (실시간-체결통보 구독 성공 시 세팅)
    private volatile String aesKey;
    private volatile String aesIv;

    public void handleMessage(String data) {
        // 1. JSON 인지, 실데이터 문자열인지 구분
        if (data.startsWith("{")) {
            handleControlJson(data);
        } else {
            handleRealtimeString(data);
        }
    }

    /**
     * SUBSCRIBE SUCCESS, ERROR, PINGPONG 등 JSON 응답 처리
     */
    private void handleControlJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode header = root.path("header");
            String trId = header.path("tr_id").asText();

            if ("PINGPONG".equals(trId)) {
                System.out.println("RECV PINGPONG: " + json);
                // 필요하면 여기서 pong 전송
                return;
            }

            JsonNode body = root.path("body");
            String rtCd = body.path("rt_cd").asText();
            String msg = body.path("msg1").asText();

            if (!"0".equals(rtCd)) {
                System.out.printf("KIS WS ERROR: rt_cd=%s, msg=%s%n", rtCd, msg);
                return;
            }

            System.out.printf("KIS WS SUBSCRIBE OK: tr_id=%s, msg=%s%n", trId, msg);

            // 체결통보(TR_ID)에 대해서는 AES key/iv 저장
            if (isSigningNoticeTrId(trId)) {
                JsonNode output = body.path("output");
                this.aesKey = output.path("key").asText();
                this.aesIv = output.path("iv").asText();
                System.out.printf("KIS WS AES KEY/IV 저장: key=%s, iv=%s%n", aesKey, aesIv);
            }

        } catch (Exception e) {
            throw new IllegalStateException("KIS WS JSON 응답 파싱 실패: " + json, e);
        }
    }

    private boolean isSigningNoticeTrId(String trId) {
        // 국내/해외 체결통보 TR_ID 들을 여기서 관리
        // 예시: H0STCNI0/H0STCNI9 (국내), 해외 체결통보 TR_ID도 필요시 추가
        return "H0STCNI0".equals(trId)
                || "H0STCNI9".equals(trId);
    }

    /**
     * "0|TR_ID|...|payload" 형태의 실데이터 처리
     */
    private void handleRealtimeString(String data) {
        // 예: 0|HDFSASP0|001|RNASQQQM^...  (해외주식 실시간호가)
        // 예: 1|H0STCNI0|001|<암호화된Base64문자열> (체결통보)
        char flag = data.charAt(0);  // '0' or '1'
        String[] parts = data.split("\\|", 4); // 앞 4개만 분리

        if (parts.length < 4) {
            System.out.println("KIS WS malformed realtime data: " + data);
            return;
        }

        String trId = parts[1];
        String countOrEtc = parts[2]; // 체결건수 등
        String payload = parts[3];    // 실제 데이터 (호가/체결/암호화데이터)

        // 0/1 flag에 따라 성격이 좀 다르지만, TR_ID 기준으로 처리 분기하는게 더 직관적
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
                System.out.printf("KIS WS unknown tr_id=%s, raw=%s%n", trId, data);
        }
    }

    /**
     * 해외주식 실시간호가 (평문) 파싱 – '^' 기반 split
     */
    private void handleOverseasQuote(String payload) {
        // 공백 필드까지 포함해서 자르기
        String[] f = payload.split("\\^", -1);

        // RSYM ~ DASK1 까지 최소 17개 필드 필요
        if (f.length < 17) {
            System.err.println("해외호가 필드 개수 부족: " + payload);
            return;
        }

        // 0 ~ 10 : 헤더/총량
        String realtimeSymbol = f[0];              // RSYM
        String symbol = f[1];              // SYMB
        int decimalPlaces = parseIntSafe(f[2]); // ZDIV
        String localDate = f[3];              // XYMD
        String localTime = f[4];              // XHMS
        String krDate = f[5];              // KYMD
        String krTime = f[6];              // KHMS
        long totalBidVolume = parseLongSafe(f[7]);  // BVOL
        long totalAskVolume = parseLongSafe(f[8]);  // AVOL
        long totalBidVolumeChange = parseLongSafe(f[9]);  // BDVL
        long totalAskVolumeChange = parseLongSafe(f[10]); // ADVL

        // 11 ~ 16 : 1호가
        double bidPrice1 = parseDoubleSafe(f[11]); // PBID1
        double askPrice1 = parseDoubleSafe(f[12]); // PASK1
        long bidVolume1 = parseLongSafe(f[13]);   // VBID1
        long askVolume1 = parseLongSafe(f[14]);   // VASK1
        long bidVolumeChange1 = parseLongSafe(f[15]);   // DBID1
        long askVolumeChange1 = parseLongSafe(f[16]);   // DASK1

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
                askVolumeChange1
        );

        // 테스트용 로그
//        System.out.printf(
//                "해외호가 수신: rsym=%s, sym=%s, bid=%.4f, ask=%.4f, KR=%s %s%n",
//                quote.realtimeSymbol(),
//                quote.symbol(),
//                quote.bidPrice1(),
//                quote.askPrice1(),
//                quote.krDate(),
//                quote.krTime()
//        );

        // 여기서 서비스/캐시/이벤트 퍼블리시 등으로 넘기기
        priceProvider.updatePrice(quote.symbol(), BigDecimal.valueOf(quote.bidPrice1()));
    }


    /**
     * 국내 체결가 예시 – 평문 '^' split
     */
    private void handleDomesticTick(String countStr, String payload) {
        int tickCount = Integer.parseInt(countStr);
        String[] fields = payload.split("\\^");
        // tickCount와 fields 길이를 보고 루프 돌면서 처리 (Python 예제와 동일한 패턴)
        // ...
    }

    /**
     * 암호화된 체결통보 payload 복호화 + '^' 파싱
     */
    private void handleSigningNoticeEncrypted(String encryptedPayload) {
        if (aesKey == null || aesIv == null) {
            System.out.println("AES KEY/IV 미설정 상태에서 체결통보 수신 – 무시: " + encryptedPayload);
            return;
        }

        String decrypted = KisAesUtil.decryptAesCbcBase64(aesKey, aesIv, encryptedPayload);
        String[] fields = decrypted.split("\\^");

        // KIS 문서의 "실시간 체결통보" Layout 순서대로 필드 매핑
        // 예시: 고객ID | 계좌번호 | 주문번호 | ...
        String customerId = fields[0];
        String accountNo = fields[1];
        String orderNo = fields[2];
        String origOrderNo = fields[3];
        String buySellType = fields[4];
        // ... 이후 필요한 만큼 필드 사용

        System.out.printf("체결통보 수신: 계좌=%s, 주문번호=%s, 매도/매수=%s%n",
                accountNo, orderNo, buySellType);

        // 여기서 이벤트 발행 or 서비스 콜 해서 알림/DB 기록 등 처리
    }

    private int parseIntSafe(String v) {
        if (v == null || v.isBlank()) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLongSafe(String v) {
        if (v == null || v.isBlank()) return 0L;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double parseDoubleSafe(String v) {
        if (v == null || v.isBlank()) return 0d;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

}
