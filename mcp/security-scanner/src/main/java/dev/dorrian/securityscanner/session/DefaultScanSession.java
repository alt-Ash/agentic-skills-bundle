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
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Port of {@code createScanSession} (http-client.ts) — the TS version is a closure over mutable
 * local state; this is the direct object-oriented equivalent, with the same constants and the
 * same trip conditions.
 *
 * <p>Java's {@link HttpClient} configures redirect-following per-client, not per-request, so
 * this class uses one non-following client and walks redirects itself when {@link
 * RequestOptions#followRedirects()} is set — the observable behavior (manual vs. auto-follow
 * 3xx) matches the TS version's per-call {@code redirect: 'manual'} override.
 *
 * <p>Redirects are never delegated to the JDK client: following ones are walked by hand so every
 * hop's {@code host[:port]} is checked against the host policy first. A hop outside it is not
 * contacted — the 3xx response that pointed there is returned instead.
 */
final class DefaultScanSession implements ScanSession {

    private static final int DEFAULT_MAX_REQUESTS = 500;
    private static final int HARD_REQUEST_CEILING = 2000;
    private static final long DEFAULT_MIN_DELAY_MS = 200;
    private static final int DEFAULT_MAX_CONCURRENCY = 2;
    private static final int CIRCUIT_WINDOW_SIZE = 20;
    private static final double CIRCUIT_ERROR_RATE_THRESHOLD = 0.4;
    private static final int CIRCUIT_LATENCY_MULTIPLIER = 3;
    /**
     * Floor for the latency baseline. A sub-millisecond first response (common on loopback)
     * records a 0ms baseline, and {@code avg >= 0 * 3} would trip on the very first request;
     * below ~10ms, clock resolution and scheduler jitter swamp any real slowdown signal anyway.
     */
    static final long MIN_BASELINE_LATENCY_MS = 10;
    /** A single slow outlier (JIT warm-up, GC) shouldn't abort the scan from a 1-2 sample average. */
    static final int MIN_LATENCY_SAMPLES = 5;
    /** Bound on redirect hops followed per request. */
    private static final int MAX_REDIRECT_HOPS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(5000);

    private final String target;
    private final int maxRequests;
    private final long minDelayMs;

    private final HttpClient client;
    private final Predicate<String> hostAllowed;
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
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.hostAllowed = opts.hostAllowed() != null ? opts.hostAllowed() : this::isTarget;
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

            long start = System.currentTimeMillis();
            try {
                URI uri = URI.create(url);
                if (!hostAllowed.test(authorityOf(uri))) {
                    return null;
                }
                HttpResponse<String> response = send(uri, options);
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

    /** Sends the request, walking redirects manually (bounded) when the options ask to follow them. */
    private HttpResponse<String> send(URI initial, RequestOptions options) throws IOException, InterruptedException {
        URI current = initial;
        Map<String, String> headers = options.headers();
        HttpResponse<String> response = sendOnce(current, headers);
        for (int hop = 0; options.followRedirects() && hop < MAX_REDIRECT_HOPS && isRedirect(response.statusCode()); hop++) {
            URI base = current;
            URI next = response.headers().firstValue("location").map(l -> resolve(base, l)).orElse(null);
            if (next == null || !hostAllowed.test(authorityOf(next))) {
                return response;
            }
            if (!authorityOf(next).equals(authorityOf(current))) {
                headers = Map.of();
            }
            current = next;
            response = sendOnce(current, headers);
        }
        return response;
    }

    private HttpResponse<String> sendOnce(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET();
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** Resolves a Location value; returns null for anything that is not an http(s) URL. */
    private static URI resolve(URI base, String location) {
        try {
            URI next = base.resolve(location);
            String scheme = next.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return web && next.getHost() != null ? next : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String authorityOf(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    private boolean isTarget(String authority) {
        return authority.equals(target.trim().toLowerCase(Locale.ROOT));
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
        if (window.size() >= MIN_LATENCY_SAMPLES && latencyTripped(avgLatency, baselineLatencyMs.get())) {
            aborted.set(true);
        }
    }

    /** {@code baselineMs < 0} means no baseline has been recorded yet. */
    static boolean latencyTripped(double avgLatencyMs, long baselineMs) {
        if (baselineMs < 0) {
            return false;
        }
        long effectiveBaseline = Math.max(baselineMs, MIN_BASELINE_LATENCY_MS);
        return avgLatencyMs >= effectiveBaseline * (double) CIRCUIT_LATENCY_MULTIPLIER;
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
