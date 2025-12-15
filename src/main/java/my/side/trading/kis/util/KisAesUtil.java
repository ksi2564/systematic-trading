package my.side.trading.kis.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

public final class KisAesUtil {

//    private KisAesUtil() {}

    /**
     * KIS WebSocket 체결통보용 AES-256-CBC + Base64 복호화
     *
     * @param key        SUBSCRIBE SUCCESS 응답의 body.output.key
     * @param iv         SUBSCRIBE SUCCESS 응답의 body.output.iv (16바이트)
     * @param cipherText 실데이터 문자열 중 암호화된 payload (Base64 인코딩됨)
     */
    public static String decryptAesCbcBase64(String key, String iv, String cipherText) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            // 공식 문서 기준 32바이트 키 (AES-256)
            if (keyBytes.length != 32) {
                keyBytes = Arrays.copyOf(keyBytes, 32);
            }

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] decoded = Base64.getDecoder().decode(cipherText);
            byte[] decrypted = cipher.doFinal(decoded);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("KIS WebSocket AES 복호화 실패", e);
        }
    }
}
