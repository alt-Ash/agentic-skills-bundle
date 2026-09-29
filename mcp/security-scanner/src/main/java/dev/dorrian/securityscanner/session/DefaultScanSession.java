package dev.dorrian.securityscanner.session;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Port of {@code createScanSession} (http-client.ts) — the TS version is a closure over mutable
 * local state; this is the direct object-oriented equivalent, with the same constants and the
 * same trip conditions.
 *
 * <p>Java's {@link HttpClient} configures redirect-following per-client, not per-request, so
 * this class keeps two clients (one following redirects, one not) and picks based on {@link
 * RequestOptions#followRedirects()} — the observable behavior (manual vs. auto-follow 3xx) is
 * identical to the TS version's per-call {@code redirect: 'manual'} override.
 */
final class DefaultScanSession implements ScanSession {

    private static final int DEFAULT_MAX_REQUESTS = 500;
    private static final int HARD_REQUEST_CEILING = 2000;
    private static final long DEFAULT_MIN_DELAY_MS = 200;
    private static final int DEFAULT_MAX_CONCURRENCY = 2;
    private static final int CIRCUIT_WINDOW_SIZE = 20;
    private static final double CIRCUIT_ERROR_RATE_THRESHOLD = 0.4;
    private static final int CIRCUIT_LATENCY_MULTIPLIER = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(5000);

    private final String target;
    private final int maxRequests;
    private final long minDelayMs;

    private final HttpClient followingClient;
    private final HttpClient manualClient;
    private final Semaphore concurrencySlots;

    private final List<RequestRecord> records = new ArrayList<>();
    private final Object recordsLock = new Object();
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicLong baselineLatencyMs = new AtomicLong(-1);
    private final ReentrantLock pacingLock = new ReentrantLock();
    private volatile long lastRequestAt = 0;

    DefaultScanSession(ScanSessionOptions opts) {
        this.target = opts.target();
        this.maxRequests = Math.min(opts.maxRequests() != null ? opts.maxRequests() : DEFAULT_MAX_REQUESTS,
            HARD_REQUEST_CEILING);
        this.minDelayMs = opts.minDelayMs() != null ? opts.minDelayMs() : DEFAULT_MIN_DELAY_MS;
        int maxConcurrency = opts.maxConcurrency() != null ? opts.maxConcurrency() : DEFAULT_MAX_CONCURRENCY;
        this.concurrencySlots = new Semaphore(maxConcurrency, true);
        this.followingClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.manualClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public ScanResponse request(String pathOrUrl) {
        return request(pathOrUrl, RequestOptions.defaults());
    }

    @Override
    public ScanResponse request(String pathOrUrl, RequestOptions options) {
        if (aborted.get()) {
            return null;
        }
        synchronized (recordsLock) {
            if (records.size() >= maxRequests) {
                aborted.set(true);
                return null;
            }
        }

        try {
            concurrencySlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        try {
            waitForPacing();

            String url = pathOrUrl.startsWith("http") ? pathOrUrl : "http://" + target + pathOrUrl;
            HttpClient client = options.followRedirects() ? followingClient : manualClient;

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
            options.headers().forEach(builder::header);
            HttpRequest request = builder.build();

            long start = System.currentTimeMillis();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = System.currentTimeMillis() - start;

                int status = response.statusCode();
                boolean strictOk = status >= 200 && status < 300;
                boolean breakerOk = status < 500;

                Map<String, String> headers = new LinkedHashMap<>();
                response.headers().map().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(", ", values)));

                baselineLatencyMs.compareAndSet(-1, latencyMs);
                recordAndEvaluate(new RequestRecord(pathOrUrl, String.valueOf(status), latencyMs, breakerOk));

                return new ScanResponse(strictOk, status, headers, response.body(), latencyMs);
            } catch (HttpTimeoutException e) {
                long latencyMs = System.currentTimeMillis() - start;
                baselineLatencyMs.compareAndSet(-1, latencyMs);
                recordAndEvaluate(new RequestRecord(pathOrUrl, "timeout", latencyMs, false));
                return null;
            } catch (IOException e) {
                long latencyMs = System.currentTimeMillis() - start;
                baselineLatencyMs.compareAndSet(-1, latencyMs);
                recordAndEvaluate(new RequestRecord(pathOrUrl, "error", latencyMs, false));
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long latencyMs = System.currentTimeMillis() - start;
                baselineLatencyMs.compareAndSet(-1, latencyMs);
                recordAndEvaluate(new RequestRecord(pathOrUrl, "error", latencyMs, false));
                return null;
            }
        } finally {
            concurrencySlots.release();
        }
    }

    private void waitForPacing() {
        pacingLock.lock();
        try {
            long elapsed = System.currentTimeMillis() - lastRequestAt;
            if (elapsed < minDelayMs) {
                try {
                    Thread.sleep(minDelayMs - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        } finally {
            pacingLock.unlock();
        }
    }

    private void recordAndEvaluate(RequestRecord record) {
        synchronized (recordsLock) {
            records.add(record);
            evaluateBreaker();
        }
    }

    /** Caller must hold {@link #recordsLock}. */
    private void evaluateBreaker() {
        int windowStart = Math.max(0, records.size() - CIRCUIT_WINDOW_SIZE);
        List<RequestRecord> window = records.subList(windowStart, records.size());
        if (window.isEmpty()) {
            return;
        }

        long errorCount = window.stream().filter(r -> !r.ok()).count();
        double errorRate = (double) errorCount / window.size();
        if (errorRate >= CIRCUIT_ERROR_RATE_THRESHOLD) {
            aborted.set(true);
            return;
        }

        double avgLatency = window.stream().mapToLong(RequestRecord::latencyMs).average().orElse(0);
        long baseline = baselineLatencyMs.get();
        if (baseline >= 0 && avgLatency >= baseline * (double) CIRCUIT_LATENCY_MULTIPLIER) {
            aborted.set(true);
        }
    }

    @Override
    public Stats getStats() {
        synchronized (recordsLock) {
            return new Stats(records.size(), aborted.get(), List.copyOf(records));
        }
    }

    @Override
    public boolean isAborted() {
        return aborted.get();
    }
}
