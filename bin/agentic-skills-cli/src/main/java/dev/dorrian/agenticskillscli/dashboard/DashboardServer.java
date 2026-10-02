package dev.dorrian.agenticskillscli.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Local, read-only dashboard over the usage database. Binds to loopback only, serves GET only,
 * rejects requests whose Host header isn't the loopback address (DNS-rebinding defence), and opens
 * the database read-only per request. Static files come from the classpath {@code /dashboard/}
 * folder via a fixed allow-list, never from a request-derived path.
 */
public final class DashboardServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> STATIC = Map.of(
        "/", "index.html",
        "/index.html", "index.html",
        "/app.js", "app.js",
        "/app.css", "app.css",
        "/vendor/chart.umd.min.js", "vendor/chart.umd.min.js");
    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8",
        "js", "text/javascript; charset=utf-8",
        "css", "text/css; charset=utf-8");

    private final HttpServer server;
    private final Path dbPath;
    private final int port;
    private final InstalledContent installed;

    private DashboardServer(HttpServer server, Path dbPath, InstalledContent installed) {
        this.server = server;
        this.dbPath = dbPath;
        this.installed = installed;
        this.port = server.getAddress().getPort();
    }

    /** Starts on loopback. {@code port} 0 picks a free one. */
    public static DashboardServer start(Path dbPath, int port) throws IOException {
        return start(dbPath, port, null);
    }

    /** As {@link #start(Path, int)}, with the bundle's installed names (null: show used-only). */
    static DashboardServer start(Path dbPath, int port, InstalledContent installed) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        DashboardServer s = new DashboardServer(http, dbPath, installed);
        http.createContext("/", s::handle);
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dashboard-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return s;
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ─── request handling ───────────────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!hostAllowed(ex.getRequestHeaders().getFirst("Host"))) {
                send(ex, 403, "text/plain; charset=utf-8", "Forbidden host".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(ex, path);
            } else {
                staticFile(ex, path);
            }
        } catch (Exception e) {
            send(ex, 500, "text/plain; charset=utf-8", "Internal error".getBytes(StandardCharsets.UTF_8));
        } finally {
            ex.close();
        }
    }

    private boolean hostAllowed(String host) {
        if (host == null) return false;
        Set<String> ok = Set.of("127.0.0.1:" + port, "localhost:" + port);
        return ok.contains(host.toLowerCase());
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String resource = STATIC.get(path);
        if (resource == null) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard/" + resource)) {
            if (in == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ext = resource.substring(resource.lastIndexOf('.') + 1);
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), in.readAllBytes());
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Filters filters;
        boolean compare;
        try {
            filters = Filters.parse(query);
            compare = Filters.comparePrev(query);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
            return;
        }
        if (path.equals("/api/export/sessions.csv") || path.equals("/api/export/events.csv")) {
            export(ex, path, filters);
            return;
        }
        Function<DashboardQueries, Object> handler = route(path, filters, compare);
        if (handler == null) {
            sendJson(ex, 404, Map.of("error", "Unknown endpoint"));
            return;
        }
        try (UsageDb db = UsageDb.openReadOnly(dbPath)) {
            sendJson(ex, 200, handler.apply(new DashboardQueries(db)));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (UsageStoreException e) {
            sendJson(ex, 503, Map.of("error", e.getMessage()));
        }
    }

    private static final List<String> SESSION_COLUMNS = List.of("session_id", "project", "user", "started_at", "ended_at",
        "prompts", "tool_calls", "failures", "peak_context", "tokens_processed", "output_tokens", "cache_read_tokens",
        "lines_added", "lines_deleted");
    private static final List<String> EVENT_COLUMNS = List.of("ts", "event", "session_id", "project", "model", "tool",
        "slash_command", "skill_name", "agent_name", "guard_rule", "input_tokens", "cached_tokens", "output_tokens",
        "cache_read_tokens", "cache_creation_tokens", "duration_ms", "command", "error");

    /** CSV download of the filtered sessions or events, capped at {@link DashboardQueries#EXPORT_CAP} rows. */
    private void export(HttpExchange ex, String path, Filters f) throws IOException {
        boolean events = path.endsWith("events.csv");
        try (UsageDb db = UsageDb.openReadOnly(dbPath)) {
            DashboardQueries q = new DashboardQueries(db);
            int cap = DashboardQueries.EXPORT_CAP;
            List<Map<String, Object>> rows = events ? q.exportEvents(f) : q.sessions(f, cap + 1);
            boolean truncated = rows.size() > cap;
            if (truncated) rows = rows.subList(0, cap);
            ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + (events ? "events.csv" : "sessions.csv") + "\"");
            ex.getResponseHeaders().set("X-Export-Truncated", String.valueOf(truncated));
            send(ex, 200, "text/csv; charset=utf-8", Csv.write(events ? EVENT_COLUMNS : SESSION_COLUMNS, rows));
        } catch (UsageStoreException e) {
            sendJson(ex, 503, Map.of("error", e.getMessage()));
        }
    }

    private Function<DashboardQueries, Object> route(String path, Filters f, boolean compare) {
        if (path.startsWith("/api/sessions/")) {
            String id = URLDecoder.decode(path.substring("/api/sessions/".length()), StandardCharsets.UTF_8);
            return q -> q.sessionEvents(id);
        }
        return switch (path) {
            case "/api/filters" -> DashboardQueries::filters;
            case "/api/summary" -> q -> compare ? q.summaryCompare(f) : q.summary(f);
            case "/api/usage" -> q -> q.usage(f, installed);
            case "/api/timeseries" -> q -> q.timeseries(f);
            case "/api/models" -> q -> q.models(f);
            case "/api/tools" -> q -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tools", q.tools(f));
                m.put("errors", q.errors(f));
                m.put("bash", q.bashCommands(f));
                return m;
            };
            case "/api/commands" -> q -> q.slashCommands(f);
            case "/api/sessions" -> q -> q.sessions(f);
            case "/api/guard" -> q -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("byRule", q.guardByRule(f));
                m.put("recent", q.guardRecent(f));
                return m;
            };
            case "/api/git" -> q -> q.hotFiles(f);
            case "/api/insights" -> q -> Insights.compute(q, f, installed);
            default -> null;
        };
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'");
        h.set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
