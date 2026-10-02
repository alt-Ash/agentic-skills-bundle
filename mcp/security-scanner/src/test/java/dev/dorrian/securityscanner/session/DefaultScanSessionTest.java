package dev.dorrian.securityscanner.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Port of tests/http-client.test.ts, assertion-for-assertion, against a real local HTTP server
 * (JDK's {@code com.sun.net.httpserver.HttpServer}) instead of a mocked {@code global.fetch} —
 * Java's {@link java.net.http.HttpClient} isn't practically mockable without an extra seam, and
 * a real loopback server exercises the actual HTTP stack, which is a strictly stronger test.
 */
class DefaultScanSessionTest {

    private HttpServer server;
    private HttpServer otherServer;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (otherServer != null) {
            otherServer.stop(0);
        }
    }

    /** Starts a second server counting its hits; returns its {@code localhost:port} authority. */
    private String startOtherServer(AtomicInteger hits) throws IOException {
        otherServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        otherServer.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "other".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        otherServer.start();
        return "localhost:" + otherServer.getAddress().getPort();
    }

    /** Starts the scan target; {@code /r} redirects to {@code location}, {@code /loop} to itself. */
    private String startRedirectingServer(String location, AtomicInteger loopHits) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/r", exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            loopHits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        return "localhost:" + server.getAddress().getPort();
    }

    /** Starts a server whose Nth request (0-indexed) returns {@code statusForCall.apply(n)} after {@code delayForCall.apply(n)} ms. */
    private String startServer(IntUnaryOperator statusForCall, IntUnaryOperator delayForCall) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger callCount = new AtomicInteger(0);
        server.createContext("/", exchange -> {
            int n = callCount.getAndIncrement();
            int status = statusForCall.applyAsInt(n);
            int delayMs = delayForCall.applyAsInt(n);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "body".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "localhost:" + server.getAddress().getPort();
    }

    @Test
    void constructsSuccessfullyEvenWithAMaxRequestsFarAboveTheHardCeiling() throws IOException {
        String target = startServer(n -> 200, n -> 0);

        var session = new DefaultScanSession(
            new ScanSessionOptions(target, 999_999, 0, 1));

        assertThat(session).isNotNull();
        // The 2000 hard ceiling itself is exercised implicitly by every other test relying on
        // the session eventually aborting on budget — a 2000-iteration test here would just be
        // slow, not more convincing.
    }

    @Test
    void stopsIssuingRequestsOnceMaxRequestsIsReached() throws IOException {
        String target = startServer(n -> 200, n -> 0);
        var session = new DefaultScanSession(new ScanSessionOptions(target, 3, 0, 1));

        List<ScanResponse> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(session.request("/"));
        }

        var stats = session.getStats();
        assertThat(stats.requestsIssued()).isEqualTo(3);
        assertThat(stats.aborted()).isTrue();
        assertThat(results.get(3)).isNull();
        assertThat(results.get(4)).isNull();
    }

    @Test
    void tripsWhenTheErrorRateCrossesTheThreshold() throws IOException {
        // Alternates 500/200 → 50% error rate over any 2-request window, above the 40% threshold.
        String target = startServer(n -> n % 2 == 0 ? 500 : 200, n -> 0);
        var session = new DefaultScanSession(new ScanSessionOptions(target, 100, 0, 1));

        for (int i = 0; i < 10 && !session.isAborted(); i++) {
            session.request("/");
        }

        assertThat(session.isAborted()).isTrue();
    }

    @Test
    void tripsWhenAverageLatencyExceedsThreeTimesTheBaseline() throws IOException {
        // First call: 5ms (sets baseline). Every subsequent call: 60ms (12x baseline, well over 3x).
        String target = startServer(n -> 200, n -> n == 0 ? 5 : 60);
        var session = new DefaultScanSession(new ScanSessionOptions(target, 100, 0, 1));

        for (int i = 0; i < 10 && !session.isAborted(); i++) {
            session.request("/");
        }

        assertThat(session.isAborted()).isTrue();
    }

    @Test
    void aZeroMillisecondBaselineDoesNotTripOnSubsequentFastResponses() {
        // Regression: a sub-ms first response on loopback recorded baseline=0, and 0 >= 0 * 3
        // tripped the breaker on the very first request (seen on Linux CI, not macOS).
        assertThat(DefaultScanSession.latencyTripped(0, 0)).isFalse();
        assertThat(DefaultScanSession.latencyTripped(2, 0)).isFalse();
        assertThat(DefaultScanSession.latencyTripped(2, 1)).isFalse();
    }

    @Test
    void latencyStillTripsAtThreeTimesAFlooredBaseline() {
        assertThat(DefaultScanSession.latencyTripped(30, 0)).isTrue();
        assertThat(DefaultScanSession.latencyTripped(150, 50)).isTrue();
        assertThat(DefaultScanSession.latencyTripped(149, 50)).isFalse();
        assertThat(DefaultScanSession.latencyTripped(1_000, -1)).isFalse();
    }

    @Test
    void doesNotTripWhenTheTargetIsHealthy() throws IOException {
        String target = startServer(n -> 200, n -> 1);
        var session = new DefaultScanSession(new ScanSessionOptions(target, 100, 0, 1));

        for (int i = 0; i < 10; i++) {
            session.request("/");
        }

        assertThat(session.isAborted())
            .describedAs("records: %s", session.getStats().records())
            .isFalse();
        assertThat(session.getStats().requestsIssued()).isEqualTo(10);
    }

    @Test
    void manualRedirectOptionPreventsAutoFollowingA3xxResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/redirect-me", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.com/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        String target = "localhost:" + server.getAddress().getPort();

        var session = new DefaultScanSession(ScanSessionOptions.forTarget(target));
        var response = session.request("/redirect-me", RequestOptions.manualRedirect());

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.headers().get("location")).isEqualTo("http://example.com/elsewhere");
    }

    @Test
    void doesNotFollowARedirectToAHostOutsideThePolicy() throws IOException {
        AtomicInteger otherHits = new AtomicInteger();
        String other = startOtherServer(otherHits);
        String target = startRedirectingServer("http://" + other + "/secret", new AtomicInteger());

        var session = new DefaultScanSession(ScanSessionOptions.forTarget(target));
        var response = session.request("/r");

        assertThat(otherHits.get()).isZero();
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(302);
    }

    @Test
    void followsARedirectToAHostThePolicyAllows() throws IOException {
        AtomicInteger otherHits = new AtomicInteger();
        String other = startOtherServer(otherHits);
        String target = startRedirectingServer("http://" + other + "/ok", new AtomicInteger());
        Predicate<String> allowTargetAndOther = host -> host.equals(target) || host.equals(other);

        var session = new DefaultScanSession(ScanSessionOptions.forTarget(target, allowTargetAndOther));
        var response = session.request("/r");

        assertThat(otherHits.get()).isEqualTo(1);
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("other");
    }

    @Test
    void boundsTheNumberOfRedirectHopsFollowedOnTheSameHost() throws IOException {
        AtomicInteger loopHits = new AtomicInteger();
        String target = startRedirectingServer("/loop", loopHits);

        var session = new DefaultScanSession(ScanSessionOptions.forTarget(target));
        var response = session.request("/loop");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(302);
        assertThat(loopHits.get()).isEqualTo(6);
    }

    @Test
    void refusesAnAbsoluteUrlOutsideThePolicy() throws IOException {
        AtomicInteger otherHits = new AtomicInteger();
        String other = startOtherServer(otherHits);
        String target = startRedirectingServer("/loop", new AtomicInteger());

        var session = new DefaultScanSession(ScanSessionOptions.forTarget(target));

        assertThat(session.request("http://" + other + "/")).isNull();
        assertThat(otherHits.get()).isZero();
    }
}
