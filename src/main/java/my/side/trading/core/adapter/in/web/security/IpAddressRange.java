package my.side.trading.core.adapter.in.web.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

final class IpAddressRange {

    private static final Pattern IP_LITERAL_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final byte[] networkAddress;
    private final int prefixLength;

    private IpAddressRange(byte[] networkAddress, int prefixLength) {
        this.networkAddress = networkAddress;
        this.prefixLength = prefixLength;
    }

    static IpAddressRange parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("trusted proxy range must not be blank");
        }

        String value = rawValue.trim();
        String[] tokens = value.split("/", 2);
        byte[] addressBytes = parseIpLiteral(tokens[0], rawValue);
        int maxPrefixLength = addressBytes.length * 8;
        int prefixLength = tokens.length == 2
                ? parsePrefixLength(tokens[1], maxPrefixLength, rawValue)
                : maxPrefixLength;

        return new IpAddressRange(addressBytes, prefixLength);
    }

    boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        byte[] candidateBytes;
        try {
            candidateBytes = parseIpLiteral(candidate.trim(), candidate);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        if (candidateBytes.length != networkAddress.length) {
            return false;
        }

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (candidateBytes[i] != networkAddress[i]) {
                return false;
            }
        }

        if (remainingBits == 0) {
            return true;
        }

        int mask = 0xFF << (8 - remainingBits);
        return (candidateBytes[fullBytes] & mask) == (networkAddress[fullBytes] & mask);
    }

    private static byte[] parseIpLiteral(String value, String rawValue) {
        if (!IP_LITERAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("IP/CIDR 형식만 허용합니다: " + rawValue);
        }

        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("유효한 IP/CIDR 형식이 아닙니다: " + rawValue, ex);
        }
    }

    private static int parsePrefixLength(String rawPrefix, int maxPrefixLength, String rawValue) {
        try {
            int parsed = Integer.parseInt(rawPrefix.trim());
            if (parsed < 0 || parsed > maxPrefixLength) {
                throw new IllegalArgumentException("CIDR prefix 범위가 잘못되었습니다: " + rawValue);
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("CIDR prefix 형식이 잘못되었습니다: " + rawValue, ex);
        }
    }
}
