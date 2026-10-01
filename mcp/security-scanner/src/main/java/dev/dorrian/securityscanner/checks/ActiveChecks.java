package dev.dorrian.securityscanner.checks;

import dev.dorrian.securityscanner.session.RequestOptions;
import dev.dorrian.securityscanner.session.ScanResponse;
import dev.dorrian.securityscanner.session.ScanSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Port of checks/active.ts — real-payload checks across 6 categories. Every documented quirk in
 * the source is preserved verbatim rather than "fixed" (see inline comments at each one) since
 * the goal is behavioral fidelity, not improvement.
 */
@Component
public class ActiveChecks {

    private static final AtomicInteger FINDING_COUNTER = new AtomicInteger(0);

    private static final List<String> COMMON_PARAMS =
        List.of("id", "q", "search", "redirect", "next", "url", "path", "file", "page");

    private static final List<ActiveCategory> ALL_CATEGORIES = List.of(
        ActiveCategory.xss, ActiveCategory.injection, ActiveCategory.open_redirect,
        ActiveCategory.path_traversal, ActiveCategory.auth_bypass, ActiveCategory.ssrf);

    private static String nextId() {
        return "AF-%02d".formatted(FINDING_COUNTER.incrementAndGet());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public List<Finding> run(ScanSession session, ActiveCheckOptions options) {
        List<Finding> findings = new ArrayList<>();
        List<ActiveCategory> categories = (options.categories() != null && !options.categories().isEmpty())
            ? options.categories()
            : ALL_CATEGORIES;

        for (ActiveCategory category : categories) {
            if (session.isAborted()) {
                break;
            }
            switch (category) {
                case xss -> xssProbe(session, findings);
                case injection -> injectionProbe(session, findings);
                case open_redirect -> openRedirectProbe(session, findings);
                case path_traversal -> pathTraversalProbe(session, findings);
                case auth_bypass -> authBypassProbe(session, options, findings);
                case ssrf -> ssrfProbe(session, findings);
            }
        }

        return findings;
    }

    private void xssProbe(ScanSession session, List<Finding> findings) {
        String payload = "<script>/*__scanner_xss_marker__*/</script>";
        for (String param : COMMON_PARAMS) {
            if (session.isAborted()) {
                break;
            }
            ScanResponse res = session.request("/?" + param + "=" + encode(payload));
            if (res != null && res.body().contains(payload)) {
                findings.add(new Finding(nextId(), "A03", Severity.HIGH,
                    "Reflected XSS: '" + param + "' parameter is reflected unescaped",
                    "GET /?" + param + "=<script marker> — the raw payload was reflected verbatim in the response body.",
                    "HTML-encode all user-supplied values before reflecting them into responses."));
            }
        }
    }

    private static final Pattern SQL_ERROR_SIGNATURE =
        Pattern.compile("sql syntax|sqlite|postgres|ORA-\\d{5}|mysql_fetch", Pattern.CASE_INSENSITIVE);

    private void injectionProbe(ScanSession session, List<Finding> findings) {
        List<String> sqlPayloads = List.of("' OR '1'='1", "' OR 1=1--");
        String noSqlPayload = "[$gt]=";

        for (String param : COMMON_PARAMS) {
            if (session.isAborted()) {
                break;
            }

            for (String payload : sqlPayloads) {
                ScanResponse res = session.request("/?" + param + "=" + encode(payload));
                if (res != null && SQL_ERROR_SIGNATURE.matcher(res.body()).find()) {
                    findings.add(new Finding(nextId(), "A03", Severity.CRITICAL,
                        "Possible SQL injection via '" + param + "' (database error signature in response)",
                        "GET /?" + param + "=" + payload + " — response body contained a database error signature.",
                        "Use parameterized queries/prepared statements; never interpolate user input into SQL."));
                    break;
                }
            }

            // Quirk preserved verbatim from the source: no "=" separator between the param name
            // and the encoded payload here, unlike every other probe's "param=value" pattern.
            ScanResponse res = session.request("/?" + param + encode(noSqlPayload));
            if (res != null && res.status() >= 200 && res.status() < 300) {
                findings.add(new Finding(nextId(), "A03", Severity.MODERATE,
                    "'" + param + "' accepts a MongoDB-operator-shaped query string without error",
                    "GET /?" + param + encode(noSqlPayload) + " → HTTP " + res.status() + " (needs manual confirmation)",
                    "Validate and sanitize query parameters; reject operator-shaped input for NoSQL backends."));
            }
        }
    }

    private void openRedirectProbe(ScanSession session, List<Finding> findings) {
        String externalTarget = "http://example.com/scanner-redirect-check";
        List<String> params = List.of("redirect", "next", "url", "return", "returnUrl");

        for (String param : params) {
            if (session.isAborted()) {
                break;
            }
            ScanResponse res = session.request("/?" + param + "=" + encode(externalTarget), RequestOptions.manualRedirect());
            String location = res != null ? res.headers().get("location") : null;
            if (res != null && res.status() >= 300 && res.status() < 400
                && location != null && location.contains("example.com")) {
                findings.add(new Finding(nextId(), "A01", Severity.MODERATE,
                    "Open redirect via '" + param + "' parameter",
                    "GET /?" + param + "=" + externalTarget + " → HTTP " + res.status() + " Location: " + location,
                    "Validate redirect targets against an allowlist of known-safe destinations."));
            }
        }
    }

    private static final Pattern ETC_PASSWD_SIGNATURE = Pattern.compile("root:.*:0:0:");

    private void pathTraversalProbe(ScanSession session, List<Finding> findings) {
        String payload = "../../../../../../etc/passwd";
        List<String> params = List.of("file", "path", "page", "template");

        for (String param : params) {
            if (session.isAborted()) {
                break;
            }
            ScanResponse res = session.request("/?" + param + "=" + encode(payload));
            if (res != null && ETC_PASSWD_SIGNATURE.matcher(res.body()).find()) {
                findings.add(new Finding(nextId(), "A03", Severity.CRITICAL,
                    "Path traversal via '" + param + "' parameter (read /etc/passwd contents)",
                    "GET /?" + param + "=" + payload + " — response body contained /etc/passwd contents.",
                    "Reject path-traversal sequences; resolve file paths against a fixed base directory."));
            }
        }
    }

    /**
     * Not itself a probe — a pure helper. Returns null if {@code token} isn't JWT-shaped (exactly
     * 3 dot-separated segments) or the header can't be decoded/re-encoded.
     */
    static String jwtAlgNoneVariant(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] headerJson = Base64.getUrlDecoder().decode(parts[0]);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var headerNode = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(headerJson);
            headerNode.put("alg", "none");
            String newHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(headerNode));
            return newHeader + "." + parts[1] + ".";
        } catch (Exception e) {
            return null;
        }
    }

    private void authBypassProbe(ScanSession session, ActiveCheckOptions options, List<Finding> findings) {
        // Dead code in the current server: no caller ever populates observedJwt. Preserved for
        // parity with the source rather than removed.
        if (options.observedJwt() != null) {
            String forged = jwtAlgNoneVariant(options.observedJwt());
            if (forged != null) {
                ScanResponse res = session.request("/", RequestOptions.withHeaders(Map.of("authorization", "Bearer " + forged)));
                if (res != null && res.status() >= 200 && res.status() < 300) {
                    findings.add(new Finding(nextId(), "A07", Severity.CRITICAL,
                        "JWT 'alg:none' forged token accepted",
                        "GET / with Authorization: Bearer <alg:none forged token> → HTTP " + res.status(),
                        "Reject tokens with alg:none; enforce a fixed, server-controlled signing algorithm."));
                }
            }
        }

        // Bounded IDOR probe — always runs. Base ID hardcoded to 1000; breaks after the first
        // successful adjacent-ID hit, so at most one IDOR finding is ever recorded per scan.
        int[] deltas = {-3, -2, -1, 1, 2, 3};
        for (int delta : deltas) {
            if (session.isAborted()) {
                break;
            }
            int id = 1000 + delta;
            ScanResponse res = session.request("/?id=" + id);
            if (res != null && res.status() >= 200 && res.status() < 300) {
                findings.add(new Finding(nextId(), "A01", Severity.MODERATE,
                    "'id' parameter accepts adjacent IDs without an observable ownership check (needs manual confirmation)",
                    "GET /?id=" + id + " → HTTP " + res.status(),
                    "Verify the authenticated caller owns/may access the referenced resource before returning it."));
                break;
            }
        }
    }

    private void ssrfProbe(ScanSession session, List<Finding> findings) {
        String canary = System.getenv("SCANNER_SSRF_CANARY_URL");
        if (canary == null || canary.isEmpty()) {
            canary = "http://127.0.0.1:1/ssrf-canary-unreachable";
        }
        List<String> params = List.of("url", "callback", "webhook", "fetch");

        for (String param : params) {
            if (session.isAborted()) {
                break;
            }
            long start = System.currentTimeMillis();
            ScanResponse res = session.request("/?" + param + "=" + encode(canary));
            long elapsed = System.currentTimeMillis() - start;
            if (res != null && elapsed > 1000) {
                findings.add(new Finding(nextId(), "A10", Severity.MODERATE,
                    "'" + param + "' parameter appears to trigger a server-side outbound request (needs manual confirmation)",
                    "GET /?" + param + "=" + canary + " took " + elapsed + "ms — consistent with the server attempting the fetch.",
                    "Validate and restrict any server-side outbound requests derived from user input (allowlist destinations)."));
            }
        }
    }
}
