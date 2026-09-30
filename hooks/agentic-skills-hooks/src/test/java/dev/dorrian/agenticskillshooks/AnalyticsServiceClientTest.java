package dev.dorrian.agenticskillshooks;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers hooks/tests/event-log.test.ts's `pushEventToService` / `pushSessionToService`
 * describe blocks. Uses the JDK-builtin com.sun.net.httpserver as a lightweight local
 * server instead of a real ANALYTICS_SERVICE_URL, and AnalyticsServiceClient's test-only
 * base-URL override seam instead of mutating process env vars.
 */
class AnalyticsServiceClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        AnalyticsServiceClient.clearBaseUrlOverrideForTesting();
        if (server != null) {
            server.stop(0);
        }
    }

    private UsageEvent sampleEvent() {
        UsageEvent event = new UsageEvent();
        event.ts = "2026-06-23T10:00:00.000Z";
        event.event = "session_start";
        event.sessionId = "sess-1";
        event.provider = "claude";
        event.user = "ashleigh";
        event.project = "/dev/project";
        return event;
    }

    @Test
    void doesNotCallOutWhenNoBaseUrlIsConfigured() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/events", ex -> {
            called.set(true);
            ex.sendResponseHeaders(202, -1);
        });
        server.start();

        AnalyticsServiceClient.clearBaseUrlOverrideForTesting();
        AnalyticsServiceClient.pushEvent(sampleEvent());
        AnalyticsServiceClient.awaitPending();

        assertFalse(called.get());
    }

    @Test
    void postsTheEventToTheConfiguredUrlWithTheCorrectBody() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/events", ex -> {
            capturedPath.set(ex.getRequestURI().getPath());
            capturedMethod.set(ex.getRequestMethod());
            capturedContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(202, -1);
            latch.countDown();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        AnalyticsServiceClient.setBaseUrlOverrideForTesting(baseUrl);
        AnalyticsServiceClient.pushEvent(sampleEvent());
        AnalyticsServiceClient.awaitPending();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("/events", capturedPath.get());
        assertEquals("POST", capturedMethod.get());
        assertEquals("application/json", capturedContentType.get());
        assertTrue(capturedBody.get().contains("\"event\":\"session_start\""));
    }

    @Test
    void postsTheSessionGroupToTheSessionsEndpoint() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/sessions", ex -> {
            capturedPath.set(ex.getRequestURI().getPath());
            capturedBody.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(202, -1);
            latch.countDown();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        AnalyticsServiceClient.setBaseUrlOverrideForTesting(baseUrl);
        AnalyticsServiceClient.pushSession("sess-1", List.of(sampleEvent()));
        AnalyticsServiceClient.awaitPending();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("/sessions", capturedPath.get());
        assertTrue(capturedBody.get().contains("\"sessionId\":\"sess-1\""));
    }

    @Test
    void swallowsErrorsWithoutThrowingWhenTheServiceIsUnreachable() {
        // Port 1 is a privileged, essentially-always-closed port — connection refused.
        AnalyticsServiceClient.setBaseUrlOverrideForTesting("http://localhost:1");
        AnalyticsServiceClient.pushEvent(sampleEvent());
        AnalyticsServiceClient.awaitPending(); // must not throw
    }
}
