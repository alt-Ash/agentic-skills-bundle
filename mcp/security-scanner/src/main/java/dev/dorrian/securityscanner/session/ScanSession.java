package dev.dorrian.securityscanner.session;

import java.util.List;

/**
 * One HTTP session scoped to a single scan invocation. Port of the TS {@code ScanSession}
 * interface (http-client.ts) — request budget, pacing, concurrency gating, and a one-way
 * circuit breaker, all scoped 1:1 to one target for the lifetime of one scan call (never reused
 * across scans).
 */
public interface ScanSession {

    /** @return null if the session is already aborted, or the request itself times out/errors. */
    ScanResponse request(String pathOrUrl);

    ScanResponse request(String pathOrUrl, RequestOptions options);

    Stats getStats();

    boolean isAborted();

    record Stats(int requestsIssued, boolean aborted, List<RequestRecord> records) {
    }
}
