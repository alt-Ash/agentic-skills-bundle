package dev.dorrian.agenticskillshooks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort secret redaction for captured Bash commands — a faithful port of the ordered
 * regex pipeline in hooks/lib/event-log.ts's redactSecrets. Order matters (each rule runs on
 * the previous rule's output). The sensitive-NAME=value rule deliberately avoids a single
 * "wildcard around an alternation" regex (catastrophic backtracking on many keyword repeats
 * with no trailing `=`) by matching identifier=value generically first, then testing the
 * captured name against the keyword list as a separate, cheap string test.
 */
public final class SecretRedactor {
    private static final int MAX_COMMAND_LENGTH = 2000;

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "TOKEN|SECRET|PASSWORD|PASSWD|API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|CREDENTIAL",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BASIC_AUTH = Pattern.compile("(://)([^/\\s:@]+):([^/\\s@]+)@");
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)[^\\s\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Pattern AWS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern SK_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]*");
    private static final Pattern CURL_U = Pattern.compile("(-u\\s+)[^\\s:\"']+:[^\\s\"']+");
    private static final Pattern NAME_VALUE = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)(\\s*=\\s*)([^\\s\"']+)");
    private static final Pattern CLI_FLAG = Pattern.compile(
            "(--?(?:password|token|api[_-]?key|secret|auth)(?:=|\\s+))[^\\s\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEM =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private SecretRedactor() {
    }

    public static String redact(String command) {
        String result = command;
        result = BASIC_AUTH.matcher(result).replaceAll("$1[REDACTED]@");
        result = BEARER.matcher(result).replaceAll("$1[REDACTED]");
        result = GITHUB_TOKEN.matcher(result).replaceAll("[REDACTED]");
        result = AWS_KEY.matcher(result).replaceAll("[REDACTED]");
        result = SK_KEY.matcher(result).replaceAll("[REDACTED]");
        result = JWT.matcher(result).replaceAll("[REDACTED]");
        result = CURL_U.matcher(result).replaceAll("$1[REDACTED]");
        result = redactSensitiveAssignments(result);
        result = CLI_FLAG.matcher(result).replaceAll("$1[REDACTED]");
        result = PEM.matcher(result).replaceAll("[REDACTED PRIVATE KEY]");

        if (result.length() > MAX_COMMAND_LENGTH) {
            result = result.substring(0, MAX_COMMAND_LENGTH) + "...[truncated]";
        }
        return result;
    }

    private static String redactSensitiveAssignments(String input) {
        Matcher m = NAME_VALUE.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String eq = m.group(2);
            String whole = m.group(0);
            String replacement = SENSITIVE_NAME.matcher(name).find()
                    ? name + eq + "[REDACTED]"
                    : whole;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
