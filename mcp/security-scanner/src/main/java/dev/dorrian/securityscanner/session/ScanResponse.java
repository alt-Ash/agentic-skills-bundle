package dev.dorrian.securityscanner.session;

import java.util.Map;

/** Port of the TS {@code ScanResponse} type. */
public record ScanResponse(boolean ok, int status, Map<String, String> headers, String body, long latencyMs) {
}
