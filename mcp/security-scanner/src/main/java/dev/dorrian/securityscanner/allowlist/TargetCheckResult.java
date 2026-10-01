package dev.dorrian.securityscanner.allowlist;

/**
 * Result of checking one host against the allowlist. Port of TS's {@code TargetCheckResult}
 * combined with {@code authorizeTarget}'s {@code & { configError?: string }} extension.
 *
 * <p>Two distinct failure shapes: {@code configError} means the allowlist file itself is
 * missing/malformed/contains an invalid environment value (a file-level problem); {@code
 * allowed=false} with a null {@code configError} but a non-null {@code reason} means the file is
 * valid but this specific host isn't in it. Callers must check {@code configError} first.
 */
public record TargetCheckResult(boolean allowed, String reason, Environment environment, String configError) {

    public static TargetCheckResult configError(String error) {
        return new TargetCheckResult(false, null, null, error);
    }

    public static TargetCheckResult allowed(Environment environment) {
        return new TargetCheckResult(true, null, environment, null);
    }

    public static TargetCheckResult notAllowed(String reason) {
        return new TargetCheckResult(false, reason, null, null);
    }
}
