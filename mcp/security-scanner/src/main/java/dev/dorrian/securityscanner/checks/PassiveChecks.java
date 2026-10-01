package dev.dorrian.securityscanner.checks;

import dev.dorrian.securityscanner.session.ScanResponse;
import dev.dorrian.securityscanner.session.ScanSession;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Port of checks/passive.ts — 5 read-only checks, run unconditionally (no confirm/authorization
 * gating, unlike active checks). Finding IDs are {@code PF-01, PF-02, …} via a process-wide
 * counter (matches the source's module-level counter, which is not reset per scan — only a
 * fresh process resets it back to 1).
 */
@Component
public class PassiveChecks {

    private static final AtomicInteger FINDING_COUNTER = new AtomicInteger(0);

    private static final Pattern REDACT_PATTERN =
        Pattern.compile("(password|token|secret|authorization|api[_-]?key)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE);

    private record SecurityHeaderSpec(String header, String category, Severity severity, String remediation) {
    }

    private static final List<SecurityHeaderSpec> SECURITY_HEADERS = List.of(
        new SecurityHeaderSpec("strict-transport-security", "A05", Severity.MODERATE,
            "Send `Strict-Transport-Security: max-age=31536000; includeSubDomains`."),
        new SecurityHeaderSpec("x-content-type-options", "A05", Severity.LOW,
            "Send `X-Content-Type-Options: nosniff`."),
        new SecurityHeaderSpec("x-frame-options", "A05", Severity.MODERATE,
            "Send `X-Frame-Options: DENY` or a CSP `frame-ancestors` directive."),
        new SecurityHeaderSpec("content-security-policy", "A05", Severity.MODERATE,
            "Define a `Content-Security-Policy` appropriate to the app.")
    );

    private static final List<String> EXPOSED_PATHS =
        List.of("/.env", "/.git/config", "/.git/HEAD", "/admin", "/.well-known/security.txt");

    private static String nextId() {
        return "PF-%02d".formatted(FINDING_COUNTER.incrementAndGet());
    }

    private static String redact(String value) {
        return REDACT_PATTERN.matcher(value).replaceAll("$1=[REDACTED]");
    }

    public List<Finding> run(ScanSession session) {
        List<Finding> findings = new ArrayList<>();

        ScanResponse root = session.request("/");

        if (root != null) {
            checkSecurityHeaders(root, findings);
            checkCookieFlags(root, findings);
            checkCors(root, findings);
            checkFingerprint(root, findings);
        }

        checkExposedPaths(session, findings);

        return findings;
    }

    private void checkSecurityHeaders(ScanResponse root, List<Finding> findings) {
        for (SecurityHeaderSpec spec : SECURITY_HEADERS) {
            if (root.headers().get(spec.header()) == null) {
                findings.add(new Finding(nextId(), spec.category(), spec.severity(),
                    "Missing security header: " + spec.header(),
                    "GET / — response headers did not include '" + spec.header() + "'.",
                    spec.remediation()));
            }
        }
    }

    private void checkCookieFlags(ScanResponse root, List<Finding> findings) {
        String setCookie = root.headers().get("set-cookie");
        if (setCookie == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String flag : List.of("secure", "httponly", "samesite")) {
            if (!setCookie.toLowerCase(java.util.Locale.ROOT).contains(flag)) {
                missing.add(flag);
            }
        }
        if (!missing.isEmpty()) {
            findings.add(new Finding(nextId(), "A05", Severity.MODERATE,
                "Cookie missing flag(s): " + String.join(", ", missing),
                redact("Set-Cookie: " + setCookie),
                "Set Secure, HttpOnly, and SameSite on every session cookie."));
        }
    }

    private void checkCors(ScanResponse root, List<Finding> findings) {
        String acao = root.headers().get("access-control-allow-origin");
        String acac = root.headers().get("access-control-allow-credentials");
        boolean wildcardWithCredentials = "*".equals(acao) && acac != null && "true".equalsIgnoreCase(acac);
        if (wildcardWithCredentials) {
            findings.add(new Finding(nextId(), "A05", Severity.HIGH,
                "CORS misconfiguration: wildcard origin combined with credentials",
                "Access-Control-Allow-Origin: * with Access-Control-Allow-Credentials: true",
                "Never combine a wildcard origin with credentialed requests — echo a validated origin instead."));
        }
    }

    private void checkFingerprint(ScanResponse root, List<Finding> findings) {
        String server = root.headers().get("server");
        String poweredBy = root.headers().get("x-powered-by");
        if (server != null || poweredBy != null) {
            List<String> parts = new ArrayList<>();
            if (server != null) {
                parts.add("Server: " + server);
            }
            if (poweredBy != null) {
                parts.add("X-Powered-By: " + poweredBy);
            }
            findings.add(new Finding(nextId(), "A05", Severity.LOW,
                "Framework/version fingerprinting via response headers",
                String.join(", ", parts),
                "Suppress or genericize the Server/X-Powered-By headers in production."));
        }
    }

    private void checkExposedPaths(ScanSession session, List<Finding> findings) {
        for (String path : EXPOSED_PATHS) {
            if (session.isAborted()) {
                break;
            }
            ScanResponse res = session.request(path);
            if (res != null && res.status() >= 200 && res.status() < 300) {
                Severity severity = (path.contains(".env") || path.contains(".git")) ? Severity.CRITICAL : Severity.MODERATE;
                findings.add(new Finding(nextId(), "A05", severity,
                    "Sensitive path is publicly reachable: " + path,
                    "GET " + path + " → HTTP " + res.status(),
                    "Ensure " + path + " is not served by the application "
                        + "(deny in web-server config, remove from the public root)."));
            }
        }
    }
}
