package my.side.trading.convention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanTextPolicyTest {

    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src/main/java"),
            Path.of("src/test/java")
    );

    private static final Pattern LEADING_COMMENT_PATTERN = Pattern.compile("^\\s*(//+|/\\*\\*?|\\*)\\s?(.*)$");
    private static final Pattern INLINE_COMMENT_PATTERN = Pattern.compile("^.*\\s+//\\s?(.*)$");
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "\\b(?:log|LOGGER)\\.(?:trace|debug|info|warn|error)\\(\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TEST_ANNOTATION_PATTERN = Pattern.compile("^\\s*@Test\\b");
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("^\\s*@");
    private static final Pattern METHOD_PATTERN = Pattern.compile("^\\s*void\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\(");
    private static final Pattern ENGLISH_PATTERN = Pattern.compile("[A-Za-z]");
    private static final Pattern HANGUL_PATTERN = Pattern.compile("[가-힣]");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_./:-]*");

    private static final List<Pattern> APPROVED_NON_KOREAN_COMMENT_PATTERNS = List.of(
            Pattern.compile("^@(?:param|return|throws)\\b.*$"),
            Pattern.compile("^(?:TODO|FIXME)\\b.*$"),
            Pattern.compile("^[A-Z0-9_ /().,:=><+\\-\"'`{}|^%]+$")
    );

    private static final List<Pattern> APPROVED_NON_KOREAN_LOG_PATTERNS = List.of(
            Pattern.compile("^\\[[A-Z0-9_ ]+\\](?:\\s+[A-Za-z][A-Za-z0-9_]*=\\{?[^\\s,]*\\}?)*(?:,\\s*[A-Za-z][A-Za-z0-9_]*=\\{?[^\\s,]*\\}?)*$"),
            Pattern.compile("^(?:[A-Za-z][A-Za-z0-9_]*=\\{?[^\\s,]*\\}?)(?:,?\\s+[A-Za-z][A-Za-z0-9_]*=\\{?[^\\s,]*\\}?)*$")
    );

    private static final Set<String> APPROVED_NON_KOREAN_TOKENS = Set.of(
            "API", "AES", "AUTO", "AUTO_LIVE", "BAQ", "BAY", "BAA", "Base64", "BLOCKED", "BUY", "CBC",
            "DD", "Domain", "DTO", "EOD", "Enum", "FIXME", "HTTP", "HTTPS", "ID", "JPA", "JSON", "KIS",
            "KPI", "MA", "MANUAL", "MANUAL_LIVE", "Mono", "NAS", "NYS", "PAPER", "PINGPONG", "Port",
            "QLD", "QQQ", "RSYM", "RT", "SELL", "TODO", "TQQQ", "TR", "TR_ID", "TTL", "URL", "VIX",
            "WebSocket", "Yahoo"
    );

    @Test
    void 주석과_로그는_한글_우선_규칙을_지킨다() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean awaitingTestMethod = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNumber = i + 1;

                if (TEST_ANNOTATION_PATTERN.matcher(line).find()) {
                    awaitingTestMethod = true;
                } else if (awaitingTestMethod) {
                    Matcher methodMatcher = METHOD_PATTERN.matcher(line);
                    if (methodMatcher.find()) {
                        validateTestMethodName(file, lineNumber, methodMatcher.group(1), violations);
                        awaitingTestMethod = false;
                    } else if (!line.isBlank() && !ANNOTATION_PATTERN.matcher(line).find()) {
                        awaitingTestMethod = false;
                    }
                }

                extractCommentText(line).ifPresent(comment ->
                        validateText(file, lineNumber, "comment", comment, APPROVED_NON_KOREAN_COMMENT_PATTERNS, violations));

                Matcher logMatcher = LOG_PATTERN.matcher(line);
                while (logMatcher.find()) {
                    validateText(file, lineNumber, "log", logMatcher.group(1), APPROVED_NON_KOREAN_LOG_PATTERNS, violations);
                }
            }
        }

        assertThat(violations)
                .withFailMessage("한글 우선 규칙 위반이 있습니다.%n%s", String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    private static List<Path> javaFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static java.util.Optional<String> extractCommentText(String line) {
        Matcher leadingMatcher = LEADING_COMMENT_PATTERN.matcher(line);
        if (leadingMatcher.find()) {
            return java.util.Optional.ofNullable(leadingMatcher.group(2)).map(String::trim);
        }

        Matcher inlineMatcher = INLINE_COMMENT_PATTERN.matcher(line);
        if (inlineMatcher.find() && !line.contains("http://") && !line.contains("https://")) {
            return java.util.Optional.ofNullable(inlineMatcher.group(1)).map(String::trim);
        }
        return java.util.Optional.empty();
    }

    private static void validateTestMethodName(Path file, int lineNumber, String methodName, List<String> violations) {
        if (containsEnglish(methodName) && !containsHangul(methodName)) {
            violations.add(formatViolation(file, lineNumber, "test", methodName));
        }
    }

    private static void validateText(
            Path file,
            int lineNumber,
            String kind,
            String text,
            List<Pattern> approvedPatterns,
            List<String> violations
    ) {
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (!containsEnglish(normalized)) {
            return;
        }
        if (containsHangul(normalized)) {
            return;
        }
        if (approvedPatterns.stream().anyMatch(pattern -> pattern.matcher(normalized).matches())) {
            return;
        }
        if (allTokensApproved(normalized)) {
            return;
        }
        violations.add(formatViolation(file, lineNumber, kind, normalized));
    }

    private static boolean allTokensApproved(String text) {
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String token = matcher.group();
            if (APPROVED_NON_KOREAN_TOKENS.contains(token)) {
                continue;
            }
            if (token.matches("[A-Z0-9_]{2,}")) {
                continue;
            }
            return false;
        }
        return found;
    }

    private static boolean containsEnglish(String text) {
        return ENGLISH_PATTERN.matcher(text).find();
    }

    private static boolean containsHangul(String text) {
        return HANGUL_PATTERN.matcher(text).find();
    }

    private static String formatViolation(Path file, int lineNumber, String kind, String text) {
        return file + ":" + lineNumber + " [" + kind + "] " + text;
    }
}
