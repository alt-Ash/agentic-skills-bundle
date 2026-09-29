package dev.dorrian.securityscanner.session;

/**
 * Port of the TS {@code ScanSessionOptions} type. The boxed {@code Integer} fields are nullable
 * to represent "use the default" — only {@code target} is ever supplied by production code
 * (the others exist purely so tests can exercise the circuit breaker/budget with tighter
 * bounds, exactly like the TS test suite does).
 */
public record ScanSessionOptions(String target, Integer maxRequests, Integer minDelayMs, Integer maxConcurrency) {

    public static ScanSessionOptions forTarget(String target) {
        return new ScanSessionOptions(target, null, null, null);
    }
}
