package dev.dorrian.agenticskillshooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget POST to ANALYTICS_SERVICE_URL, a Java port of hooks/lib/event-log.ts's
 * pushEventToService/pushSessionToService. Node's event loop naturally keeps the process alive
 * until an un-awaited fetch settles or times out; the JVM has no such guarantee once main()
 * returns, so in-flight requests are tracked here and HookDispatcher gives them a bounded
 * window (awaitPending) to actually go out over the network before the process exits — without
 * that, this fire-and-forget POST could be silently dropped on every single invocation.
 */
public final class AnalyticsServiceClient {
    private static final List<CompletableFuture<?>> PENDING = new CopyOnWriteArrayList<>();

    // Test-only seam: production always resolves from the environment (resolveBaseUrl()), but a
    // static-final env-var snapshot can't be toggled between test cases in the same JVM the way
    // the TS suite re-imports the module per describe block, so tests set this instead. A real
    // hook invocation is a fresh, single-use JVM process, so re-reading the env var on every call
    // here (rather than caching it once) costs nothing in production.
    private static volatile String baseUrlOverride;

    private AnalyticsServiceClient() {
    }

    static void setBaseUrlOverrideForTesting(String url) {
        baseUrlOverride = url;
    }

    static void clearBaseUrlOverrideForTesting() {
        baseUrlOverride = null;
    }

    private static String normalize(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String resolveBaseUrl() {
        if (baseUrlOverride != null) return normalize(baseUrlOverride);
        return normalize(System.getenv("ANALYTICS_SERVICE_URL"));
    }

    public static void pushEvent(UsageEvent event) {
        String baseUrl = resolveBaseUrl();
        if (baseUrl == null) return;
        postAsync(baseUrl + "/events", event);
    }

    public static void pushSession(String sessionId, List<UsageEvent> hooks) {
        String baseUrl = resolveBaseUrl();
        if (baseUrl == null) return;
        postAsync(baseUrl + "/sessions", Map.of("sessionId", sessionId, "hooks", hooks));
    }

    private static void postAsync(String url, Object body) {
        try {
            String json = JsonSupport.MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            CompletableFuture<?> future = HttpClient.newHttpClient()
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> null); // intentionally silent — service being down must not affect hooks
            PENDING.add(future);
        } catch (Exception ignored) {
            // intentionally silent
        }
    }

    public static void awaitPending() {
        for (CompletableFuture<?> f : PENDING) {
            try {
                f.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        PENDING.clear();
    }
}
