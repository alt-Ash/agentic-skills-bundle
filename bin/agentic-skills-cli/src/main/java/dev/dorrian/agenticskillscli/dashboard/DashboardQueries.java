package dev.dorrian.agenticskillscli.dashboard;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageStoreException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only aggregate queries behind the dashboard API. "Context tokens" for a tool call is
 * {@code input_tokens + cached_tokens} of the last assistant message the hook saw — a snapshot of
 * how big the context was, not a billed total — so sums are labelled "processed", not spend.
 */
final class DashboardQueries {

    // GROUP BY uses ordinals throughout: SQLite resolves a bare name to the events *column* before
    // a SELECT alias, so "GROUP BY command" would silently group by the raw command, not the alias.

    /** input + cached, only meaningful on tool_use rows. */
    private static final String CONTEXT = "(COALESCE(e.input_tokens,0) + COALESCE(e.cached_tokens,0))";
    private static final String TOOL_CONTEXT = "CASE WHEN e.event = 'tool_use' THEN " + CONTEXT + " END";

    private final UsageDb db;

    /** Columns of {@code events}: a database not yet migrated to schema v2 lacks the newer ones. */
    private final Set<String> columns = new HashSet<>();

    DashboardQueries(UsageDb db) {
        this.db = db;
        for (Map<String, Object> r : query("PRAGMA table_info(events)", List.of())) {
            columns.add(String.valueOf(r.get("name")));
        }
    }

    /** {@code e.<name>} when the column exists, else a typed NULL, so older databases degrade to "n/a". */
    private String col(String name) {
        return columns.contains(name) ? "e." + name : "NULL";
    }

    Map<String, Object> filters() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", column("SELECT DISTINCT project FROM events WHERE project IS NOT NULL ORDER BY project"));
        out.put("models", column("SELECT DISTINCT model FROM events WHERE model IS NOT NULL ORDER BY model"));
        Map<String, Object> range = one("SELECT MIN(ts) AS first_ts, MAX(ts) AS last_ts FROM events e WHERE 1=1", Filters.NONE);
        out.put("range", range);
        out.put("health", health());
        return out;
    }

    Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("droppedEvents", db.meta(UsageDb.DROPPED_EVENTS));
        out.put("sessionEndsWithoutStart", db.meta("session_end_without_start"));
        out.put("totalEvents", db.countEvents());
        return out;
    }

    Map<String, Object> summary(Filters f) {
        Map<String, Object> row = one("""
            SELECT COUNT(DISTINCT e.session_id) AS sessions,
                   COALESCE(SUM(e.event = 'user_prompt'), 0) AS prompts,
                   COALESCE(SUM(e.event = 'tool_use'), 0) AS tool_calls,
                   COALESCE(SUM(e.event = 'tool_failure'), 0) AS tool_failures,
                   COALESCE(SUM(e.event = 'guard_block'), 0) AS guard_blocks,
                   COALESCE(SUM(e.event = 'turn_stop'), 0) AS turns,
                   AVG(%1$s) AS avg_context,
                   MAX(%1$s) AS peak_context,
                   COALESCE(SUM(%1$s), 0) AS tokens_processed,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_added END), 0) AS lines_added,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_deleted END), 0) AS lines_deleted,
                   SUM(%2$s) AS output_tokens,
                   SUM(%3$s) AS cache_read_tokens,
                   SUM(%4$s) AS cache_creation_tokens,
                   SUM(CASE WHEN %3$s IS NOT NULL THEN COALESCE(e.input_tokens, 0) END) AS cache_input_tokens
            FROM events e WHERE 1=1""".formatted(TOOL_CONTEXT, col("output_tokens"), col("cache_read_tokens"),
            col("cache_creation_tokens")), f);
        Object cacheRead = row.get("cache_read_tokens");
        if (cacheRead instanceof Number cr) {
            double denom = cr.doubleValue() + num(row.get("cache_creation_tokens")) + num(row.get("cache_input_tokens"));
            row.put("cache_read_share", denom > 0 ? cr.doubleValue() / denom : null);
        } else {
            row.put("cache_read_share", null);
        }
        long calls = ((Number) row.get("tool_calls")).longValue();
        long failures = ((Number) row.get("tool_failures")).longValue();
        row.put("failure_rate", calls + failures == 0 ? 0.0 : (double) failures / (calls + failures));
        return row;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** The compared KPIs and whether a lower value is the good direction (null: neutral). */
    private static final Map<String, Boolean> KPIS = new LinkedHashMap<>();
    static {
        KPIS.put("sessions", null);
        KPIS.put("prompts", null);
        KPIS.put("tool_calls", null);
        KPIS.put("tool_failures", true);
        KPIS.put("failure_rate", true);
        KPIS.put("avg_context", true);
        KPIS.put("tokens_processed", null);
        KPIS.put("output_tokens", null);
        KPIS.put("cache_read_share", false);
        KPIS.put("guard_blocks", null);
        KPIS.put("lines_added", null);
    }

    /** Current window, the equal-length window right before it, and per-KPI deltas. Needs from and to. */
    Map<String, Object> summaryCompare(Filters f) {
        Filters prev = f.previous();
        Map<String, Object> cur = summary(f);
        Map<String, Object> old = summary(prev);
        Map<String, Object> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> k : KPIS.entrySet()) {
            Object a = cur.get(k.getKey());
            Object b = old.get(k.getKey());
            Map<String, Object> d = new LinkedHashMap<>();
            boolean both = a instanceof Number && b instanceof Number;
            double abs = both ? ((Number) a).doubleValue() - ((Number) b).doubleValue() : Double.NaN;
            d.put("abs", both ? abs : null);
            d.put("pct", both && ((Number) b).doubleValue() != 0 ? abs / ((Number) b).doubleValue() : null);
            d.put("lowerIsBetter", k.getValue());
            deltas.put(k.getKey(), d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", cur);
        out.put("previous", old);
        out.put("deltas", deltas);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", f.from());
        window.put("to", f.to());
        window.put("previousFrom", prev.from());
        window.put("previousTo", prev.to());
        out.put("window", window);
        return out;
    }

    List<Map<String, Object>> timeseries(Filters f) {
        return rows("""
            SELECT substr(e.ts, 1, 10) AS day,
                   SUM(e.event = 'user_prompt') AS prompts,
                   SUM(e.event = 'tool_use') AS tool_calls,
                   SUM(e.event = 'tool_failure') AS tool_failures,
                   SUM(e.event = 'guard_block') AS guard_blocks,
                   COUNT(DISTINCT e.session_id) AS sessions,
                   COALESCE(SUM(%s), 0) AS tokens_processed
            FROM events e WHERE 1=1""".formatted(TOOL_CONTEXT), f, "GROUP BY 1 ORDER BY 1");
    }

    List<Map<String, Object>> models(Filters f) {
        return rows("""
            SELECT COALESCE(e.model, 'unknown') AS model,
                   SUM(e.event = 'tool_use') AS tool_calls,
                   COALESCE(SUM(%1$s), 0) AS tokens_processed,
                   AVG(%1$s) AS avg_context,
                   MAX(%1$s) AS peak_context,
                   SUM(%2$s) AS output_tokens,
                   SUM(%3$s) AS cache_read_tokens,
                   SUM(%4$s) AS cache_creation_tokens
            FROM events e WHERE (e.event = 'tool_use' OR %2$s IS NOT NULL OR %3$s IS NOT NULL)""".formatted(TOOL_CONTEXT,
            col("output_tokens"), col("cache_read_tokens"), col("cache_creation_tokens")), f,
            "GROUP BY 1 ORDER BY tool_calls DESC");
    }

    /** Per tool: successful calls, failures and rate. Older rows without a recorded tool name group as "(not recorded)". */
    List<Map<String, Object>> tools(Filters f) {
        List<Map<String, Object>> rows = rows("""
            SELECT COALESCE(e.tool_name, '(not recorded)') AS tool,
                   SUM(e.event = 'tool_use') AS calls,
                   SUM(e.event = 'tool_failure') AS failures,
                   AVG(CASE WHEN e.event = 'tool_use' THEN e.duration_ms END) AS avg_duration_ms
            FROM events e WHERE e.event IN ('tool_use', 'tool_failure')""", f, "GROUP BY 1 ORDER BY calls + failures DESC");
        for (Map<String, Object> r : rows) {
            long calls = ((Number) r.get("calls")).longValue();
            long failures = ((Number) r.get("failures")).longValue();
            r.put("failure_rate", calls + failures == 0 ? 0.0 : (double) failures / (calls + failures));
        }
        return rows;
    }

    List<Map<String, Object>> errors(Filters f) {
        return rows("""
            SELECT COALESCE(e.tool_name, '(not recorded)') AS tool, substr(e.error, 1, 140) AS error, COUNT(*) AS count
            FROM events e WHERE e.event = 'tool_failure' AND e.error IS NOT NULL""", f,
            "GROUP BY 1, 2 ORDER BY count DESC LIMIT 15");
    }

    /** First word of recorded Bash commands (already secret-redacted at capture time). */
    List<Map<String, Object>> bashCommands(Filters f) {
        return rows("""
            SELECT CASE WHEN instr(e.command, ' ') > 0 THEN substr(e.command, 1, instr(e.command, ' ') - 1) ELSE e.command END AS command,
                   COUNT(*) AS count,
                   SUM(e.event = 'tool_failure') AS failures
            FROM events e WHERE e.command IS NOT NULL AND e.event IN ('tool_use', 'tool_failure')""", f,
            "GROUP BY 1 ORDER BY count DESC LIMIT 20");
    }

    List<Map<String, Object>> slashCommands(Filters f) {
        return rows("""
            SELECT e.slash_command AS command, COUNT(*) AS count, COUNT(DISTINCT e.session_id) AS sessions
            FROM events e WHERE e.event = 'user_prompt' AND e.slash_command IS NOT NULL""", f,
            "GROUP BY 1 ORDER BY count DESC");
    }

    /** Row cap for the CSV exports. */
    static final int EXPORT_CAP = 50_000;

    List<Map<String, Object>> sessions(Filters f) {
        return sessions(f, 300);
    }

    List<Map<String, Object>> sessions(Filters f, int limit) {
        return rows("""
            SELECT s.session_id AS session_id, s.project AS project, s.user_name AS user, s.started_at AS started_at, s.ended_at AS ended_at,
                   COALESCE(SUM(e.event = 'user_prompt'), 0) AS prompts,
                   COALESCE(SUM(e.event = 'tool_use'), 0) AS tool_calls,
                   COALESCE(SUM(e.event = 'tool_failure'), 0) AS failures,
                   MAX(%1$s) AS peak_context,
                   COALESCE(SUM(%1$s), 0) AS tokens_processed,
                   SUM(%2$s) AS output_tokens,
                   SUM(%3$s) AS cache_read_tokens,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_added END), 0) AS lines_added,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_deleted END), 0) AS lines_deleted
            FROM events e JOIN sessions s ON s.session_id = e.session_id WHERE 1=1""".formatted(TOOL_CONTEXT,
            col("output_tokens"), col("cache_read_tokens")), f,
            "GROUP BY s.session_id ORDER BY s.started_at DESC LIMIT " + limit);
    }

    List<Map<String, Object>> sessionEvents(String sessionId) {
        return query("""
            SELECT e.ts AS ts, e.event AS event, e.tool_name AS tool, e.command AS command, e.error AS error,
                   e.slash_command AS slash_command, e.guard_rule AS guard_rule, e.model AS model,
                   %s AS context_tokens, e.prompt_char_length AS prompt_chars, e.estimated_output_tokens AS output_tokens_est,
                   %s AS skill_name, %s AS agent_name, %s AS output_tokens, %s AS cache_read_tokens
            FROM events e WHERE e.session_id = ? ORDER BY e.ts, e.rowid LIMIT 2000""".formatted(CONTEXT,
            col("skill_name"), col("agent_name"), col("output_tokens"), col("cache_read_tokens")), List.of(sessionId));
    }

    /**
     * Flat event rows for the CSV export. Only fields the API already exposes (commands are
     * redacted at capture); no cwd, user or prompt text. One extra row is fetched to detect truncation.
     */
    List<Map<String, Object>> exportEvents(Filters f) {
        return rows("""
            SELECT e.ts AS ts, e.event AS event, e.session_id AS session_id, e.project AS project, e.model AS model,
                   e.tool_name AS tool, e.slash_command AS slash_command, %s AS skill_name, %s AS agent_name,
                   e.guard_rule AS guard_rule, e.input_tokens AS input_tokens, e.cached_tokens AS cached_tokens,
                   %s AS output_tokens, %s AS cache_read_tokens, %s AS cache_creation_tokens,
                   e.duration_ms AS duration_ms, e.command AS command, e.error AS error
            FROM events e WHERE 1=1""".formatted(col("skill_name"), col("agent_name"), col("output_tokens"),
            col("cache_read_tokens"), col("cache_creation_tokens")), f,
            "ORDER BY e.ts, e.rowid LIMIT " + (EXPORT_CAP + 1));
    }

    /**
     * Skills, agents and slash commands seen in the window (per name: uses, sessions, last used),
     * merged with the installed list when there is one so never-used names show up with zero uses.
     */
    Map<String, Object> usage(Filters f, InstalledContent installed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> inst = new LinkedHashMap<>();
        inst.put("available", installed != null);
        inst.put("source", installed == null ? null : installed.source());
        out.put("installed", inst);
        Map<String, Object> never = new LinkedHashMap<>();
        out.put("skills", usageKind(usedRows("skill_name", "1=1", f), installed == null ? null : installed.skills(), never, "skills"));
        out.put("agents", usageKind(usedRows("agent_name", "1=1", f), installed == null ? null : installed.agents(), never, "agents"));
        out.put("commands", usageKind(slashUsed(f), installed == null ? null : installed.commands(), never, "commands"));
        out.put("neverUsed", never);
        return out;
    }

    private List<Map<String, Object>> usedRows(String column, String extraWhere, Filters f) {
        if (!columns.contains(column)) return new ArrayList<>();
        return rows("SELECT e." + column + " AS name, COUNT(*) AS uses, COUNT(DISTINCT e.session_id) AS sessions,"
            + " MAX(e.ts) AS last_used FROM events e WHERE e." + column + " IS NOT NULL AND e." + column + " <> ''"
            + " AND " + extraWhere, f, "GROUP BY 1 ORDER BY uses DESC");
    }

    /** Slash commands stored as typed ("/plan"); merged by name without the leading slash. */
    private List<Map<String, Object>> slashUsed(Filters f) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> r : usedRows("slash_command", "e.event = 'user_prompt'", f)) {
            String name = String.valueOf(r.get("name")).replaceFirst("^/+", "");
            if (name.isEmpty()) continue;
            Map<String, Object> m = byName.get(name);
            if (m == null) {
                r.put("name", name);
                byName.put(name, r);
            } else {
                m.put("uses", ((Number) m.get("uses")).longValue() + ((Number) r.get("uses")).longValue());
                // distinct sessions can't be summed exactly across spellings; the max is a lower bound
                m.put("sessions", Math.max(((Number) m.get("sessions")).longValue(), ((Number) r.get("sessions")).longValue()));
                if (String.valueOf(r.get("last_used")).compareTo(String.valueOf(m.get("last_used"))) > 0) {
                    m.put("last_used", r.get("last_used"));
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> Long.compare(((Number) b.get("uses")).longValue(), ((Number) a.get("uses")).longValue()));
        return out;
    }

    private static List<Map<String, Object>> usageKind(List<Map<String, Object>> used, List<String> installed,
                                                       Map<String, Object> never, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (Map<String, Object> r : used) {
            usedNames.add(String.valueOf(r.get("name")));
            r.put("installed", installed == null ? null : installed.contains(String.valueOf(r.get("name"))));
            out.add(r);
        }
        if (installed != null) {
            List<String> unused = new ArrayList<>();
            for (String name : installed) {
                if (usedNames.contains(name)) continue;
                unused.add(name);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("uses", 0);
                m.put("sessions", 0);
                m.put("last_used", null);
                m.put("installed", true);
                out.add(m);
            }
            never.put(kind, unused);
        }
        return out;
    }

    List<Map<String, Object>> guardByRule(Filters f) {
        return rows("""
            SELECT e.guard_rule AS rule, COALESCE(e.tool_name, '?') AS tool, COUNT(*) AS blocks, MAX(e.ts) AS last
            FROM events e WHERE e.event = 'guard_block'""", f, "GROUP BY 1, 2 ORDER BY blocks DESC");
    }

    List<Map<String, Object>> guardRecent(Filters f) {
        return rows("""
            SELECT e.ts AS ts, e.project AS project, e.guard_rule AS rule, e.tool_name AS tool, e.command AS subject
            FROM events e WHERE e.event = 'guard_block'""", f, "ORDER BY e.ts DESC LIMIT 50");
    }

    List<Map<String, Object>> hotFiles(Filters f) {
        return rows("""
            SELECT gf.path AS path, COUNT(*) AS changes, COUNT(DISTINCT e.session_id) AS sessions
            FROM git_files gf JOIN events e ON e.event_id = gf.event_id WHERE gf.change IN ('A', 'M')""", f,
            "GROUP BY gf.path ORDER BY changes DESC, sessions DESC LIMIT 20");
    }

    // ─── plumbing ───────────────────────────────────────────────────────────

    private List<Map<String, Object>> rows(String selectWhere, Filters f, String tail) {
        Filters.Clause c = f.clause();
        return query(selectWhere + c.sql() + " " + tail, List.of(), c);
    }

    private Map<String, Object> one(String selectWhere, Filters f) {
        List<Map<String, Object>> rows = rows(selectWhere, f, "");
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private List<Object> column(String sql) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : query(sql, List.of())) out.add(r.values().iterator().next());
        return out;
    }

    private List<Map<String, Object>> query(String sql, List<String> params) {
        return query(sql, params, null);
    }

    private List<Map<String, Object>> query(String sql, List<String> params, Filters.Clause clause) {
        try (PreparedStatement p = db.connection().prepareStatement(sql)) {
            int i = 0;
            for (String param : params) p.setString(++i, param);
            if (clause != null) clause.bind(p, i);
            try (ResultSet rs = p.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        row.put(md.getColumnLabel(c), rs.getObject(c));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new UsageStoreException("Dashboard query failed: " + e.getMessage(), e);
        }
    }
}
