package dev.dorrian.agenticskillscli.dashboard;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There is no JS toolchain, so UI/API drift is caught here: every endpoint the browser code calls
 * must be served, and the browser code must never use innerHTML (the data is untrusted text).
 */
class DashboardUiContractTest {

    private static final Pattern API_CALL = Pattern.compile("\\bapi\\('([^']+)'");
    private static final Pattern API_LITERAL = Pattern.compile("['\"=](/api/[A-Za-z0-9_./-]+)");

    private final HttpClient http = HttpClient.newHttpClient();
    private DashboardServer server;

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("u.db");
        try (UsageDb u = UsageDb.open(db)) {
            UsageEvent e = new UsageEvent();
            e.eventId = "1";
            e.sessionId = "s";
            e.event = "user_prompt";
            e.ts = "2026-10-01T09:00:00Z";
            u.record(e);
        }
        server = DashboardServer.start(db, 0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = DashboardUiContractTest.class.getResourceAsStream("/dashboard/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int status(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void everyApiPathAppJsCallsIsServed() throws Exception {
        String js = resource("app.js");
        Set<String> paths = new LinkedHashSet<>();
        Matcher m = API_CALL.matcher(js);
        while (m.find()) paths.add(m.group(1));
        assertTrue(paths.size() >= 8, "expected to find the api() calls, found " + paths);
        for (String p : paths) {
            // a trailing '?...' is a literal query (e.g. sessions/<id>?x=1); a trailing '/' is a prefix
            // the code completes with an id: both must still route (not 404).
            int code = status("/api/" + p);
            assertEquals(200, code, "app.js calls api('" + p + "') but the server answered " + code);
        }
    }

    @Test
    void everyApiLinkInTheHtmlAndJsIsServed() throws Exception {
        Set<String> links = new LinkedHashSet<>();
        for (String file : new String[] {"index.html", "app.js"}) {
            Matcher m = API_LITERAL.matcher(resource(file));
            while (m.find()) links.add(m.group(1));
        }
        assertTrue(links.contains("/api/export/sessions.csv"), links.toString());
        for (String l : links) {
            assertEquals(200, status(l), l);
        }
    }

    @Test
    void theBrowserCodeNeverUsesInnerHtmlOrFriends() throws Exception {
        for (String file : new String[] {"app.js", "index.html"}) {
            String src = resource(file);
            assertFalse(src.contains("innerHTML"), file + " must render via textContent, never innerHTML");
            assertFalse(src.contains("outerHTML"), file);
            assertFalse(src.contains("insertAdjacentHTML"), file);
            assertFalse(src.contains("document.write"), file);
            assertFalse(src.contains("eval("), file);
        }
    }
}
