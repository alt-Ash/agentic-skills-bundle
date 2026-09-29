package dev.dorrian.securityscanner.checks;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.dorrian.securityscanner.session.ScanSessionFactory;
import dev.dorrian.securityscanner.session.ScanSession;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PassiveChecksTest {

    private HttpServer server;
    private final PassiveChecks checks = new PassiveChecks();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(Map<String, String> headers, List<String> okPaths) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            boolean ok = path.equals("/") || okPaths.contains(path);
            byte[] body = "hello".getBytes();
            exchange.sendResponseHeaders(ok ? 200 : 404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "localhost:" + server.getAddress().getPort();
    }

    @Test
    void reportsAllFourMissingSecurityHeadersWhenNoneArePresent() throws IOException {
        String target = startServer(Map.of(), List.of());
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        assertThat(findings).extracting(Finding::description)
            .contains(
                "Missing security header: strict-transport-security",
                "Missing security header: x-content-type-options",
                "Missing security header: x-frame-options",
                "Missing security header: content-security-policy");
    }

    @Test
    void doesNotReportAHeaderThatIsPresent() throws IOException {
        String target = startServer(Map.of("Strict-Transport-Security", "max-age=31536000"), List.of());
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        assertThat(findings).extracting(Finding::description)
            .doesNotContain("Missing security header: strict-transport-security");
    }

    @Test
    void flagsCookieMissingSecureHttpOnlySameSiteAndRedactsToken() throws IOException {
        String target = startServer(Map.of("Set-Cookie", "session=abc; token=deadbeef"), List.of());
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        var cookieFinding = findings.stream().filter(f -> f.description().startsWith("Cookie missing flag")).findFirst();
        assertThat(cookieFinding).isPresent();
        assertThat(cookieFinding.get().description()).contains("secure", "httponly", "samesite");
        assertThat(cookieFinding.get().evidence()).contains("[REDACTED]").doesNotContain("deadbeef");
    }

    @Test
    void flagsCorsOnlyWhenWildcardOriginCombinedWithCredentialsTrue() throws IOException {
        String target = startServer(Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Credentials", "true"
        ), List.of());
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        assertThat(findings).anyMatch(f -> f.description().contains("CORS misconfiguration"));
    }

    @Test
    void doesNotFlagCorsWhenOnlyWildcardOriginIsPresentWithoutCredentials() throws IOException {
        String target = startServer(Map.of("Access-Control-Allow-Origin", "*"), List.of());
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        assertThat(findings).noneMatch(f -> f.description().contains("CORS misconfiguration"));
    }

    @Test
    void flagsServerFingerprintingWhenServerHeaderPresent() throws IOException {
        String target = startServer(Map.of("Server", "nginx/1.18.0"), List.of());
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        assertThat(findings).anyMatch(f -> f.description().contains("fingerprinting"));
    }

    @Test
    void flagsExposedEnvFileAsCriticalAndAdminPathAsModerate() throws IOException {
        String target = startServer(Map.of(), List.of("/.env", "/admin"));
        ScanSession session = new ScanSessionFactory().create(target);

        List<Finding> findings = checks.run(session);

        var envFinding = findings.stream().filter(f -> f.description().contains("/.env")).findFirst();
        var adminFinding = findings.stream().filter(f -> f.description().contains("/admin")).findFirst();
        assertThat(envFinding).isPresent();
        assertThat(envFinding.get().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(adminFinding).isPresent();
        assertThat(adminFinding.get().severity()).isEqualTo(Severity.MODERATE);
    }
}
