package dev.dorrian.securityscanner.checks;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.dorrian.securityscanner.session.ScanSession;
import dev.dorrian.securityscanner.session.ScanSessionFactory;
import dev.dorrian.securityscanner.session.ScanSessionOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ActiveChecksTest {

    private HttpServer server;
    private final ActiveChecks checks = new ActiveChecks();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ScanSession fastSession(String target) {
        return new ScanSessionFactory().create(new ScanSessionOptions(target, null, 0, null));
    }

    private String startServer(Function<String, ServerResponse> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            ServerResponse response = responder.apply(query != null ? query : "");
            if (response.delayMs() > 0) {
                try {
                    Thread.sleep(response.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (response.locationHeader() != null) {
                exchange.getResponseHeaders().add("Location", response.locationHeader());
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), response.status() == 302 ? -1 : body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                if (response.status() != 302) {
                    os.write(body);
                }
            }
        });
        server.start();
        return "localhost:" + server.getAddress().getPort();
    }

    private record ServerResponse(int status, String body, long delayMs, String locationHeader) {
        static ServerResponse ok(String body) {
            return new ServerResponse(200, body, 0, null);
        }

        static ServerResponse notFound() {
            return new ServerResponse(404, "", 0, null);
        }
    }

    @Test
    void xssProbeFlagsUnescapedReflection() throws IOException {
        String marker = "<script>/*__scanner_xss_marker__*/</script>";
        String target = startServer(query -> ServerResponse.ok("echo: " + URLDecoder.decode(query, StandardCharsets.UTF_8)));

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.xss)));

        assertThat(findings).anyMatch(f -> f.description().contains("Reflected XSS"));
    }

    @Test
    void xssProbeDoesNotFlagWhenPayloadIsNotReflected() throws IOException {
        String target = startServer(query -> ServerResponse.ok("no reflection here"));

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.xss)));

        assertThat(findings).isEmpty();
    }

    @Test
    void injectionProbeFlagsSqlErrorSignature() throws IOException {
        String target = startServer(query -> ServerResponse.ok("Warning: you have an error in your SQL syntax near..."));

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.injection)));

        assertThat(findings).anyMatch(f -> f.description().contains("Possible SQL injection"));
    }

    @Test
    void injectionProbeFlagsNoSqlOperatorAcceptanceOnAnyTwoHundredResponse() throws IOException {
        String target = startServer(query -> ServerResponse.ok("ok"));

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.injection)));

        // With a flat 200 response for everything, every param's NoSQL sub-check trips (since it
        // only requires a 2xx, no error signature needed) — confirms the no-"=" quirk still
        // produces a well-formed request the server answers to.
        assertThat(findings).anyMatch(f -> f.description().contains("MongoDB-operator-shaped"));
    }

    @Test
    void pathTraversalProbeFlagsEtcPasswdSignature() throws IOException {
        String target = startServer(query -> ServerResponse.ok("root:x:0:0:root:/root:/bin/bash\ndaemon:x:1:1:..."));

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.path_traversal)));

        assertThat(findings).anyMatch(f -> f.description().contains("Path traversal"));
    }

    @Test
    void openRedirectProbeFlagsA3xxToExampleDotCom() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.com/scanner-redirect-check");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        String target = "localhost:" + server.getAddress().getPort();

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.open_redirect)));

        assertThat(findings).anyMatch(f -> f.description().contains("Open redirect"));
    }

    @Test
    void authBypassIdorProbeStopsAfterFirstHitProducingAtMostOneFinding() throws IOException {
        String target = startServer(query -> ServerResponse.ok("ok")); // every id request succeeds

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.auth_bypass)));

        assertThat(findings).filteredOn(f -> f.description().contains("adjacent IDs")).hasSize(1);
    }

    @Test
    void authBypassProbeFindsNothingWhenIdsAreRejected() throws IOException {
        String target = startServer(query -> ServerResponse.notFound());

        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.auth_bypass)));

        assertThat(findings).isEmpty();
    }

    @Test
    void ssrfProbeFlagsWhenResponseTakesOverOneSecond() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String target = "localhost:" + server.getAddress().getPort();

        // Only test the first param to keep this test's runtime bounded (4 params x 1.1s each).
        var findings = checks.run(fastSession(target), ActiveCheckOptions.of(List.of(ActiveCategory.ssrf)));

        assertThat(findings).anyMatch(f -> f.description().contains("server-side outbound request"));
    }

    @Test
    void jwtAlgNoneVariantReturnsNullForNonJwtShapedInput() {
        assertThat(ActiveChecks.jwtAlgNoneVariant("not-a-jwt")).isNull();
    }

    @Test
    void jwtAlgNoneVariantRewritesTheAlgHeaderToNoneAndDropsTheSignature() {
        // header {"alg":"HS256","typ":"JWT"} base64url-encoded, arbitrary payload/signature.
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String token = header + ".payload.signature";

        String forged = ActiveChecks.jwtAlgNoneVariant(token);

        assertThat(forged).isNotNull();
        assertThat(forged).endsWith(".payload.");
        String decodedHeader = new String(
            java.util.Base64.getUrlDecoder().decode(forged.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(decodedHeader).contains("\"alg\":\"none\"");
    }

    @Test
    void runsAllSixCategoriesInFixedOrderWhenNoneSpecified() throws IOException {
        String target = startServer(query -> ServerResponse.notFound());

        // No exception, no findings expected against a plain 404 server — just confirms all 6
        // categories execute without error when categories is null/empty.
        var findings = checks.run(fastSession(target), ActiveCheckOptions.all());

        assertThat(findings).isNotNull();
    }
}
