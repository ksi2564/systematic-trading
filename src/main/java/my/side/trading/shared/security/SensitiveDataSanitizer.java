package my.side.trading.shared.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class SensitiveDataSanitizer {

    private static final int DEFAULT_MAX_LENGTH = 500;
    private static final int THROWABLE_MAX_LENGTH = 300;

    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(\\bauthorization\\b\\s*[:=]\\s*)(?!Bearer\\s+\\*\\*\\*)[^,}\\s]+");
    private static final Pattern WEBHOOK_URL = Pattern.compile(
            "(?i)https?://[^\\s\"']*/webhooks?/[^\\s\"']+");
    private static final Pattern JSON_SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\"(?:approval_key|approvalKey|access_token|accessToken|app_key|appKey|appkey|"
                    + "app_secret|appSecret|appsecret|api_key|apiKey|authorization|CANO|ACNT_PRDT_CD|"
                    + "account_no|accountNo|account|aes_key|aesKey|aes_iv|aesIv|iv|key|secret|password)\"\\s*:\\s*\")"
                    + "([^\"]*)"
                    + "(\")");
    private static final Pattern KEY_VALUE_SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\\b(?:approval[_-]?key|access[_-]?token|app[_-]?key|appkey|app[_-]?secret|appsecret|"
                    + "api[_-]?key|CANO|ACNT_PRDT_CD|account[_-]?no|account|aes[_-]?key|"
                    + "aes[_-]?iv|key|iv|secret|password)\\b\\s*[:=]\\s*)"
                    + "([^,}\\s]+)");
    private static final Pattern KOREAN_ACCOUNT_FIELD = Pattern.compile(
            "(계좌(?:번호)?\\s*[:=]\\s*)\\d{6,}");

    private SensitiveDataSanitizer() {
    }

    public static String sanitize(String value) {
        return sanitize(value, DEFAULT_MAX_LENGTH);
    }

    public static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = BEARER_TOKEN.matcher(value).replaceAll("Bearer ***");
        sanitized = WEBHOOK_URL.matcher(sanitized).replaceAll("https://***/webhooks/***");
        sanitized = AUTHORIZATION_VALUE.matcher(sanitized).replaceAll("$1***");
        sanitized = JSON_SENSITIVE_FIELD.matcher(sanitized).replaceAll("$1***$3");
        sanitized = KEY_VALUE_SENSITIVE_FIELD.matcher(sanitized).replaceAll("$1***");
        sanitized = KOREAN_ACCOUNT_FIELD.matcher(sanitized).replaceAll("$1***");
        return truncate(sanitized, maxLength);
    }

    public static String sanitizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "-";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + sanitize(message, THROWABLE_MAX_LENGTH);
    }

    public static Map<String, String> sanitizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    public static String sanitizeValue(String key, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return "***";
        }
        return sanitize(value);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toLowerCase();
        return normalized.contains("approvalkey")
                || normalized.contains("accesstoken")
                || normalized.contains("appkey")
                || normalized.contains("appsecret")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.equals("cano")
                || normalized.equals("acntprdtd")
                || normalized.equals("acntprdtcd")
                || normalized.contains("account")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("aeskey")
                || normalized.contains("aesiv")
                || normalized.equals("key")
                || normalized.equals("iv");
    }

    private static String truncate(String value, int maxLength) {
        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated, length=" + value.length() + ")";
    }
}
