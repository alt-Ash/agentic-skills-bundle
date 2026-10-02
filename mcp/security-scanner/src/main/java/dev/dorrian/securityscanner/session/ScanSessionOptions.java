package dev.dorrian.securityscanner.session;

import java.util.function.Predicate;

/**
 * Port of the TS {@code ScanSessionOptions} type. The boxed {@code Integer} fields are nullable
 * to represent "use the default" — only {@code target} is ever supplied by production code
 * (the others exist purely so tests can exercise the circuit breaker/budget with tighter
 * bounds, exactly like the TS test suite does).
 *
 * <p>{@code hostAllowed} decides whether a redirect hop (or absolute URL) may be contacted; it
 * receives the {@code host[:port]} authority. {@code null} means "only the scan target itself".
 */
public record ScanSessionOptions(String target, Integer maxRequests, Integer minDelayMs,
                                 Integer maxConcurrency, Predicate<String> hostAllowed) {

    public ScanSessionOptions(String target, Integer maxRequests, Integer minDelayMs, Integer maxConcurrency) {
        this(target, maxRequests, minDelayMs, maxConcurrency, null);
    }

    public static ScanSessionOptions forTarget(String target, Predicate<String> hostAllowed) {
        return new ScanSessionOptions(target, null, null, null, hostAllowed);
    }

    public static ScanSessionOptions forTarget(String target) {
        return new ScanSessionOptions(target, null, null, null);
    }
}
