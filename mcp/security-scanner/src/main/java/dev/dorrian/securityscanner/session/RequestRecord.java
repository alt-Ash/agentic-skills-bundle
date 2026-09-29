package dev.dorrian.securityscanner.session;

/**
 * Port of the TS {@code RequestRecord} type. {@code status} is either the HTTP status code as
 * a string, or one of the literal strings {@code "timeout"}/{@code "error"} — mirrors the TS
 * union {@code number | 'timeout' | 'error'} without needing a sealed hierarchy for such a
 * simple case.
 */
public record RequestRecord(String path, String status, long latencyMs, boolean ok) {
}
